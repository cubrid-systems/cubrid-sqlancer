package sqlancer.cubrid.gen;

import java.util.List;
import java.util.stream.Collectors;

import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.cubrid.CUBRIDErrors;
import sqlancer.cubrid.CUBRIDProvider;
import sqlancer.cubrid.CUBRIDSchema;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDColumn;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDCompositeDataType;

/**
 * {@code ALTER TABLE}.
 *
 * <p>
 * 여기서 가장 값이 큰 것은 {@code ADD COLUMN} 이다. 기존 행에는 NULL 이 채워지므로 값이 있는 테이블 안에 NULL 만 든 컬럼이 생기고, 그 뒤에
 * {@code CREATE INDEX} 가 붙으면 인덱스와 실제 데이터가 어긋난 상태가 만들어진다 — 기록된 세 번째 결함이 사는 자리가 정확히 거기다.
 *
 * <p>
 * 컬럼을 지우거나 타입을 바꾸면 그 컬럼에 걸린 인덱스도 다시 만들어진다. 통계와 접근 경로가 스키마 변경을 따라오지 못하는 순간을 노린다.
 */
public final class CUBRIDAlterTableGenerator {

    private CUBRIDAlterTableGenerator() {
    }

    private enum Action {
        ADD_COLUMN, DROP_COLUMN, RENAME_COLUMN, CHANGE_TYPE, ADD_UNIQUE
    }

    public static SQLQueryAdapter getQuery(CUBRIDProvider.CUBRIDGlobalState globalState) {
        ExpectedErrors errors = new ExpectedErrors();
        StringBuilder sb = new StringBuilder();

        CUBRIDSchema.CUBRIDTable table = globalState.getSchema().getRandomTable(t -> !t.isView());
        List<CUBRIDColumn> columns = table.getColumns();
        sb.append("ALTER TABLE ");
        sb.append(table.getName());
        sb.append(" ");

        Action action = Randomly.fromOptions(Action.values());
        // 마지막 컬럼은 지울 수 없다.
        if (action == Action.DROP_COLUMN && columns.size() < 2) {
            action = Action.ADD_COLUMN;
        }

        switch (action) {
        case ADD_COLUMN:
            sb.append("ADD COLUMN ");
            sb.append(freeColumnName(columns));
            sb.append(" ");
            sb.append(CUBRIDCompositeDataType.getRandomStorable().toSqlString());
            break;
        case DROP_COLUMN:
            sb.append("DROP COLUMN ");
            sb.append(Randomly.fromList(columns).getName());
            break;
        case RENAME_COLUMN:
            sb.append("RENAME COLUMN ");
            sb.append(Randomly.fromList(columns).getName());
            sb.append(" AS ");
            sb.append(freeColumnName(columns));
            break;
        case CHANGE_TYPE: {
            // CUBRID 는 도메인 변경을 좁게만 허용한다 — 대부분 "changing to new domain" 으로 거부된다.
            String name = Randomly.fromList(columns).getName();
            sb.append("CHANGE COLUMN ");
            sb.append(name).append(" ").append(name).append(" ");
            sb.append(CUBRIDCompositeDataType.getRandomStorable().toSqlString());
            errors.add("changing to new domain");
            errors.add("Cannot change attribute");
            break;
        }
        default:
            sb.append("ADD CONSTRAINT ");
            sb.append(globalState.getSchema().getFreeIndexName());
            sb.append(" UNIQUE (");
            sb.append(table.getRandomNonEmptyColumnSubset().stream().map(CUBRIDColumn::getName)
                    .collect(Collectors.joining(", ")));
            sb.append(")");
            // 동등한 인덱스가 이미 있으면 CUBRID 가 거부한다. 무작위 데이터에 중복이 있으면 당연히 실패한다.
            errors.add("unique constraint");
            errors.add("duplicate");
            errors.add("Duplicate");
            break;
        }
        sb.append(";");

        CUBRIDErrors.addExpressionErrors(errors);
        errors.add("already exists");
        errors.add("already defined");
        errors.add("not found");
        return new SQLQueryAdapter(sb.toString(), errors, true);
    }

    /** 컬럼 이름은 {@code cN} 이다. 쓰이지 않은 가장 작은 N 을 고른다. */
    private static String freeColumnName(List<CUBRIDColumn> columns) {
        for (int i = 0;; i++) {
            String candidate = "c" + i;
            if (columns.stream().noneMatch(c -> c.getName().equalsIgnoreCase(candidate))) {
                return candidate;
            }
        }
    }
}
