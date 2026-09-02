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
import sqlancer.cubrid.CUBRIDSchema.CUBRIDDataType;
import sqlancer.cubrid.CUBRIDToStringVisitor;

/**
 * {@code CREATE VIEW v AS SELECT ...}
 *
 * <p>
 * 뷰는 옵티마이저에 판단을 하나 더 강요한다 — 뷰 정의를 바깥 질의에 <em>병합</em>할지, 아니면 따로 계산해 물리화할지다. 뷰에 {@code WHERE}
 * 가 있으면 그 술어를 바깥 술어와 합쳐야 하고, 합치는 규칙을 틀리면 행이 사라지거나 늘어난다. 이미 찾은 두 결함이 모두 조인 재작성 계열이라 같은
 * 지점을 다른 각도에서 누르는 셈이다.
 *
 * <p>
 * {@code GROUP BY} 가 붙은 뷰는 병합이 불가능해 물리화를 강제하므로, 두 경로를 모두 만든다.
 */
public final class CUBRIDViewGenerator {

    private CUBRIDViewGenerator() {
    }

    public static SQLQueryAdapter getQuery(CUBRIDProvider.CUBRIDGlobalState globalState) {
        ExpectedErrors errors = new ExpectedErrors();
        StringBuilder sb = new StringBuilder();

        // 뷰 위의 뷰도 허용한다 — 중첩이야말로 병합 판단이 어려워지는 지점이다.
        CUBRIDSchema.CUBRIDTable base = globalState.getSchema().getRandomTable();
        List<CUBRIDColumn> columns = base.getRandomNonEmptyColumnSubset();

        sb.append("CREATE VIEW ");
        sb.append(globalState.getSchema().getFreeViewName());
        sb.append(" AS SELECT ");
        // 식이 아니라 순수 컬럼만 고른다. 뷰의 컬럼 이름은 카탈로그로 되읽히므로, 식을 쓰면 별칭 없이는
        // 참조할 수 없는 이름이 생긴다.
        sb.append(columns.stream().map(CUBRIDColumn::getName).collect(Collectors.joining(", ")));
        sb.append(" FROM ");
        sb.append(base.getName());

        if (Randomly.getBoolean()) {
            CUBRIDExpressionGenerator gen = new CUBRIDExpressionGenerator(globalState)
                    .setColumns(base.getColumns());
            sb.append(" WHERE ");
            sb.append(CUBRIDToStringVisitor.asString(
                    gen.generateExpression(CUBRIDCompositeDataType.getRandomWithType(CUBRIDDataType.BOOLEAN))));
        } else if (Randomly.getBooleanWithRatherLowProbability()) {
            // 집계 뷰. 병합할 수 없으니 물리화 경로를 탄다.
            sb.append(" GROUP BY ");
            sb.append(columns.stream().map(CUBRIDColumn::getName).collect(Collectors.joining(", ")));
        }
        sb.append(";");

        CUBRIDErrors.addExpressionErrors(errors);
        errors.add("already exists");
        // 이름은 클래스 이름 공간을 테이블과 공유한다.
        errors.add("already defined");
        return new SQLQueryAdapter(sb.toString(), errors, true);
    }
}
