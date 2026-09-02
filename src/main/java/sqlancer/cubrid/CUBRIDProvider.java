package sqlancer.cubrid;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import com.google.auto.service.AutoService;

import sqlancer.AbstractAction;
import sqlancer.DatabaseProvider;
import sqlancer.IgnoreMeException;
import sqlancer.MainOptions;
import sqlancer.Randomly;
import sqlancer.SQLConnection;
import sqlancer.SQLGlobalState;
import sqlancer.SQLProviderAdapter;
import sqlancer.StatementExecutor;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.query.SQLQueryProvider;
import sqlancer.cubrid.gen.CUBRIDAlterTableGenerator;
import sqlancer.cubrid.gen.CUBRIDDeleteGenerator;
import sqlancer.cubrid.gen.CUBRIDIndexGenerator;
import sqlancer.cubrid.gen.CUBRIDInsertGenerator;
import sqlancer.cubrid.gen.CUBRIDTableGenerator;
import sqlancer.cubrid.gen.CUBRIDUpdateGenerator;
import sqlancer.cubrid.gen.CUBRIDViewGenerator;

/**
 * CUBRID provider.
 *
 * <h2>다른 provider 와 다른 점 — 데이터베이스를 만들지 않는다</h2>
 *
 * CUBRID 에는 {@code CREATE DATABASE} SQL 문이 없다. 데이터베이스는 OS 명령 {@code cubrid createdb} 로만 만들어진다. 그래서 이
 * provider 는 미리 준비된 하나의 테스트 DB 에 접속해 그 안의 <em>사용자 테이블만</em> 지우고 다시 만든다.
 *
 * DB 준비는 {@code scripts/cubrid-testdb.sh} 가 담당한다 (생성 / 재사용 / 재생성 선택 가능).
 *
 * <p>
 * <b>결과적으로 {@code --num-threads} 는 1 이어야 한다.</b> 모든 스레드가 같은 DB 의 같은 테이블을 지우고 만들기 때문이다. 1 보다 크면 시작 시 거부한다.
 */
@AutoService(DatabaseProvider.class)
public class CUBRIDProvider extends SQLProviderAdapter<CUBRIDProvider.CUBRIDGlobalState, CUBRIDOptions> {

    private static final String CUBRID = "cubrid";
    private static final String DRIVER_CLASS = "cubrid.jdbc.driver.CUBRIDDriver";

    public CUBRIDProvider() {
        super(CUBRIDGlobalState.class, CUBRIDOptions.class);
    }

    public enum Action implements AbstractAction<CUBRIDGlobalState> {
        INSERT(CUBRIDInsertGenerator::getQuery),
        UPDATE(CUBRIDUpdateGenerator::getQuery),
        DELETE(CUBRIDDeleteGenerator::getQuery),
        /**
         * 인덱스가 없으면 옵티마이저가 고를 접근 경로가 하나뿐이라 NoREC 이 볼 재작성 자체가 거의 없다. 탐지력의 지렛대는 여기다.
         */
        CREATE_INDEX(CUBRIDIndexGenerator::getQuery),
        /**
         * 뷰는 옵티마이저에 병합이냐 물리화냐를 판단하게 만든다. 지금까지 찾은 결함이 모두 재작성 계열이라 같은 지점을 다른 각도에서 누른다.
         */
        CREATE_VIEW(CUBRIDViewGenerator::getQuery),
        /**
         * ADD COLUMN 은 기존 행에 NULL 을 채운다. 값이 있는 테이블 안의 NULL 컬럼에 인덱스가 붙는 상태가 만들어지고, 기록된 세 번째 결함이
         * 사는 자리가 그곳이다.
         */
        ALTER_TABLE(CUBRIDAlterTableGenerator::getQuery);

        private final SQLQueryProvider<CUBRIDGlobalState> sqlQueryProvider;

        Action(SQLQueryProvider<CUBRIDGlobalState> sqlQueryProvider) {
            this.sqlQueryProvider = sqlQueryProvider;
        }

        @Override
        public SQLQueryAdapter getQuery(CUBRIDGlobalState state) throws Exception {
            return sqlQueryProvider.getQuery(state);
        }
    }

    @Override
    public SQLConnection createDatabase(CUBRIDGlobalState globalState) throws Exception {
        MainOptions options = globalState.getOptions();
        if (options.getNumberConcurrentThreads() > 1) {
            throw new AssertionError("CUBRID provider 는 단일 스레드만 지원한다 (--num-threads 1). "
                    + "CUBRID 는 SQL 로 DB 를 만들 수 없어 모든 스레드가 같은 테스트 DB 를 공유하기 때문이다.");
        }

        String host = options.getHost();
        if (host == null) {
            host = CUBRIDOptions.DEFAULT_HOST;
        }
        int port = options.getPort();
        if (port == MainOptions.NO_SET_PORT) {
            port = CUBRIDOptions.DEFAULT_PORT;
        }
        String user = options.getUserName();
        if (user == null || user.isEmpty()) {
            user = CUBRIDOptions.DEFAULT_USER;
        }
        String password = options.getPassword() == null ? CUBRIDOptions.DEFAULT_PASSWORD : options.getPassword();
        String database = globalState.getDbmsSpecificOptions().testDatabase;

        // jdbc:CUBRID:<host>:<port>:<db>:<user>:<password>:
        String url = String.format("jdbc:CUBRID:%s:%d:%s:%s:%s:", host, port, database, user, password);
        globalState.getState().logStatement("-- " + url);

        Class.forName(DRIVER_CLASS);
        Connection connection = DriverManager.getConnection(url);
        dropAllUserTables(connection, globalState);
        return new SQLConnection(connection);
    }

    /** 준비된 DB 를 빈 상태로 되돌린다. CUBRID 에 DROP DATABASE 가 없으므로 테이블 단위로 지운다. */
    private void dropAllUserTables(Connection connection, CUBRIDGlobalState globalState) throws SQLException {
        List<String> views = new ArrayList<>();
        List<String> tables = new ArrayList<>();
        try (Statement s = connection.createStatement();
                ResultSet rs = s.executeQuery("SELECT class_name, class_type FROM db_class "
                        + "WHERE is_system_class = 'NO'")) {
            while (rs.next()) {
                if ("VCLASS".equals(rs.getString("class_type"))) {
                    views.add(rs.getString("class_name"));
                } else {
                    tables.add(rs.getString("class_name"));
                }
            }
        }
        // 뷰가 테이블을 참조하므로 뷰부터.
        try (Statement s = connection.createStatement()) {
            for (String v : views) {
                String stmt = "DROP VIEW IF EXISTS " + v;
                globalState.getState().logStatement(stmt);
                s.execute(stmt);
            }
            for (String t : tables) {
                String stmt = "DROP TABLE IF EXISTS " + t;
                globalState.getState().logStatement(stmt);
                s.execute(stmt);
            }
        }
    }

    @Override
    public String getDBMSName() {
        return CUBRID;
    }

    @Override
    public void generateDatabase(CUBRIDGlobalState globalState) throws Exception {
        // 조인은 테이블이 2개 이상일 때만 만들어진다. 1개짜리 DB 에서는 조인이 원리적으로 불가능해
        // 옵티마이저가 고를 조인 순서도, 그것을 틀릴 여지도 없다.
        for (int i = 0; i < Randomly.fromOptions(2, 3, 4); i++) {
            boolean success;
            int attempts = 0;
            do {
                if (++attempts > 100) {
                    throw new IgnoreMeException();
                }
                SQLQueryAdapter qt = new CUBRIDTableGenerator().getQuery(globalState, null);
                success = globalState.executeStatement(qt);
            } while (!success);
        }
        if (globalState.getSchema().getDatabaseTables().isEmpty()) {
            throw new IgnoreMeException();
        }
        StatementExecutor<CUBRIDGlobalState, Action> se = new StatementExecutor<>(globalState, Action.values(),
                CUBRIDProvider::mapActions, (q) -> {
                    if (globalState.getSchema().getDatabaseTables().isEmpty()) {
                        throw new IgnoreMeException();
                    }
                });
        se.executeStatements();
    }

    private static int mapActions(CUBRIDGlobalState globalState, Action a) {
        Randomly r = globalState.getRandomly();
        switch (a) {
        case INSERT:
            return r.getInteger(0, globalState.getOptions().getMaxNumberInserts());
        case UPDATE:
            return r.getInteger(0, 10);
        case DELETE:
            return r.getInteger(0, 5);
        case CREATE_INDEX:
            return r.getInteger(0, 5);
        case CREATE_VIEW:
            return r.getInteger(0, 2);
        case ALTER_TABLE:
            return r.getInteger(0, 3);
        default:
            throw new AssertionError(a);
        }
    }

    public static class CUBRIDGlobalState extends SQLGlobalState<CUBRIDOptions, CUBRIDSchema> {

        @Override
        protected CUBRIDSchema readSchema() throws SQLException {
            return CUBRIDSchema.fromConnection(getConnection(), getDatabaseName());
        }
    }
}
