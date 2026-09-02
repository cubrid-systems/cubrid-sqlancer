package sqlancer.cubrid.gen;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import sqlancer.Randomly;
import sqlancer.common.gen.AbstractGenerator;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.cubrid.CUBRIDErrors;
import sqlancer.cubrid.CUBRIDProvider;
import sqlancer.cubrid.CUBRIDSchema;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDColumn;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDCompositeDataType;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDDataType;
import sqlancer.cubrid.CUBRIDToStringVisitor;

/**
 * {@code CREATE [UNIQUE] INDEX ... ON t (c [ASC|DESC], ...) [WHERE pred]}
 *
 * <p>
 * NoREC 의 관심사는 옵티마이저의 재작성이다. 인덱스가 없으면 옵티마이저가 고를 접근 경로가 하나뿐이라 재작성할 여지도 거의 없다. 인덱스를 만들어야
 * 술어가 인덱스 스캔으로 접히는 경로와 순차 스캔 경로가 갈리고, 그 둘이 어긋나는 순간이 곧 wrong-result 다.
 *
 * <p>
 * filtered index({@code WHERE} 절이 붙은 인덱스)는 특히 값이 크다 — 옵티마이저가 "이 인덱스를 이 질의에 써도 되는가"를 판단해야 하고, 그
 * 판단이 틀리면 행이 사라지거나 늘어난다.
 */
public final class CUBRIDIndexGenerator extends AbstractGenerator {

    private final CUBRIDProvider.CUBRIDGlobalState globalState;

    private CUBRIDIndexGenerator(CUBRIDProvider.CUBRIDGlobalState globalState) {
        this.globalState = globalState;
        this.canAffectSchema = true;
    }

    public static SQLQueryAdapter getQuery(CUBRIDProvider.CUBRIDGlobalState globalState) {
        return new CUBRIDIndexGenerator(globalState).getStatement();
    }

    @Override
    public void buildStatement() {
        CUBRIDSchema.CUBRIDTable table = globalState.getSchema().getRandomTable(t -> !t.isView());
        List<CUBRIDColumn> columns = table.getRandomNonEmptyColumnSubset();

        sb.append("CREATE ");
        if (Randomly.getBooleanWithRatherLowProbability()) {
            sb.append("UNIQUE ");
            // 무작위 데이터에 중복이 있으면 UNIQUE 는 당연히 실패한다 — 버그가 아니다.
            errors.add("duplicate");
            errors.add("Duplicate");
            errors.add("unique constraint");
        }
        sb.append("INDEX ");
        sb.append(globalState.getSchema().getFreeIndexName());
        sb.append(" ON ");
        sb.append(table.getName());
        sb.append("(");
        sb.append(columns.stream().map(c -> c.getName() + (Randomly.getBoolean() ? " ASC" : " DESC"))
                .collect(Collectors.joining(", ")));
        sb.append(")");

        if (Randomly.getBooleanWithRatherLowProbability()) {
            // filtered index. 술어는 이 테이블의 컬럼만 참조해야 한다.
            CUBRIDExpressionGenerator gen = new CUBRIDExpressionGenerator(globalState)
                    .setColumns(new ArrayList<>(table.getColumns()));
            sb.append(" WHERE ");
            sb.append(CUBRIDToStringVisitor.asString(
                    gen.generateExpression(CUBRIDCompositeDataType.getRandomWithType(CUBRIDDataType.BOOLEAN))));
            // filtered index 는 술어에 쓸 수 있는 형태가 제한된다.
            errors.add("Semantic:");
            errors.add("not allowed");
            errors.add("invalid");
        }
        sb.append(";");
        CUBRIDErrors.addExpressionErrors(errors);
        errors.add("already exists");
        // 스키마 캐시와 실제 상태가 한 박자 어긋나면 이름이 겹칠 수 있다.
        errors.add("already defined");
    }
}
