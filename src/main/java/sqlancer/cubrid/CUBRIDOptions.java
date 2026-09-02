package sqlancer.cubrid;

import java.util.List;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import sqlancer.DBMSSpecificOptions;

@Parameters(commandDescription = "CUBRID (default port: 33000)")
public class CUBRIDOptions implements DBMSSpecificOptions<CUBRIDOracleFactory> {

    public static final String DEFAULT_HOST = "localhost";
    /** CUBRID broker port. cubrid_broker.conf 의 BROKER_PORT 기본값. */
    public static final int DEFAULT_PORT = 33000;
    public static final String DEFAULT_USER = "dba";
    public static final String DEFAULT_PASSWORD = "";

    @Parameter(names = "--oracle")
    public List<CUBRIDOracleFactory> oracle = List.of(CUBRIDOracleFactory.NOREC);

    /**
     * CUBRID 는 SQL 로 데이터베이스를 만들 수 없다 (CREATE DATABASE 문이 없다). 따라서 SQLancer 가 매 iteration 마다 DB 를 새로
     * 만드는 대신, 미리 준비된 하나의 DB 에 접속해 그 안의 *테이블* 만 지우고 다시 만든다.
     *
     * DB 준비는 scripts/cubrid-testdb.sh 가 담당한다.
     */
    @Parameter(names = "--test-database", description = "미리 준비된 CUBRID 테스트 DB 이름")
    public String testDatabase = "sqlancer";

    @Override
    public List<CUBRIDOracleFactory> getTestOracleFactory() {
        return oracle;
    }
}
