package sqlancer.cubrid.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * SQLancer 세션 로그를 <b>JDBC 경로로</b> 재생한다.
 *
 * <p>
 * 왜 csql 이 아니라 JDBC 인가: SQLancer 는 {@code JDBC -> broker -> CAS -> cub_server} 로 붙고, {@code csql} 은
 * 서버에 직접 붙는다. 두 경로가 통과하는 코드가 다르므로, CAS 경유에서만 나는 크래시는 csql 재생으로 재현되지 않는다. 실제로 크래시 세션 319문장을
 * csql 로 3회 재생했을 때 서버는 죽지 않았다.
 *
 * <p>
 * 한 문장이 실패해도 계속 진행한다 — 무작위 SQL 은 원래 상당수가 실패하며, 크래시는 그 뒤에 온다.
 *
 * <pre>
 * java -cp "target/classes:target/deps/*" sqlancer.cubrid.tools.CUBRIDReplay \
 *      --file statements.sql --host localhost --port 34001 --db sqlancer
 * </pre>
 *
 * 종료 코드: 0 = 끝까지 실행, 3 = 연결이 끊겨 더 진행할 수 없음(서버가 죽었을 가능성).
 */
public final class CUBRIDReplay {

    private static final String DRIVER_CLASS = "cubrid.jdbc.driver.CUBRIDDriver";

    private CUBRIDReplay() {
    }

    public static void main(String[] args) throws Exception {
        String file = null;
        String host = "localhost";
        int port = 34001;
        String db = "sqlancer";
        String user = "dba";
        String password = "";
        boolean verbose = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
            case "--file": file = args[++i]; break;
            case "--host": host = args[++i]; break;
            case "--port": port = Integer.parseInt(args[++i]); break;
            case "--db": db = args[++i]; break;
            case "--user": user = args[++i]; break;
            case "--password": password = args[++i]; break;
            case "--verbose": verbose = true; break;
            default: throw new IllegalArgumentException("unknown option: " + args[i]);
            }
        }
        if (file == null) {
            System.err.println("usage: CUBRIDReplay --file <sql> [--host h] [--port p] [--db name]"
                    + " [--user u] [--password p] [--verbose]");
            System.exit(2);
        }

        List<String> statements = readStatements(Path.of(file));
        String url = String.format("jdbc:CUBRID:%s:%d:%s:%s:%s:", host, port, db, user, password);
        Class.forName(DRIVER_CLASS);

        int executed = 0;
        int failed = 0;
        try (Connection connection = DriverManager.getConnection(url)) {
            for (String sql : statements) {
                try (Statement s = connection.createStatement()) {
                    s.execute(sql);
                    executed++;
                } catch (SQLException e) {
                    failed++;
                    if (verbose) {
                        System.out.println("-- failed: " + firstLine(e.getMessage()));
                    }
                    // 연결 자체가 끊겼으면 서버가 죽은 것이다. 더 진행할 수 없다.
                    if (connection.isClosed() || !isUsable(connection)) {
                        System.out.printf("executed=%d failed=%d  connection lost at statement %d%n",
                                executed, failed, executed + failed);
                        System.out.println("-- last: " + truncate(sql));
                        System.exit(3);
                    }
                }
            }
        } catch (SQLException e) {
            System.out.printf("executed=%d failed=%d  connect/close failed: %s%n", executed, failed,
                    firstLine(e.getMessage()));
            System.exit(3);
        }
        System.out.printf("executed=%d failed=%d  completed%n", executed, failed);
    }

    private static boolean isUsable(Connection connection) {
        try (Statement s = connection.createStatement()) {
            s.execute("SELECT 1 FROM db_root");
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    /** 한 줄에 한 문장. 빈 줄과 주석 줄은 버리고, 끝의 세미콜론도 뗀다. */
    private static List<String> readStatements(Path p) throws IOException {
        return Files.readAllLines(p, StandardCharsets.UTF_8).stream().map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("--"))
                .map(l -> l.endsWith(";") ? l.substring(0, l.length() - 1) : l).collect(java.util.stream.Collectors.toList());
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "(no message)";
        }
        int nl = s.indexOf('\n');
        return truncate(nl < 0 ? s : s.substring(0, nl));
    }

    private static String truncate(String s) {
        return s.length() <= 160 ? s : s.substring(0, 160) + "...";
    }
}
