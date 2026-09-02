package sqlancer.cubrid.gen;

import java.util.ArrayList;

import sqlancer.Randomly;
import sqlancer.common.gen.AbstractGenerator;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.cubrid.CUBRIDErrors;
import sqlancer.cubrid.CUBRIDProvider;
import sqlancer.cubrid.CUBRIDSchema;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDCompositeDataType;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDDataType;
import sqlancer.cubrid.CUBRIDToStringVisitor;

/**
 * {@code DELETE FROM t [WHERE pred]}
 *
 * <p>
 * INSERT 와 UPDATE 만으로는 테이블이 단조롭게 자라기만 한다. DELETE 가 있어야 페이지에 구멍이 생기고, 인덱스에서 지워진 키가 남고, 통계가
 * 실제와 어긋난다 — 옵티마이저가 잘못 판단하기 좋은 상태다.
 */
public final class CUBRIDDeleteGenerator extends AbstractGenerator {

    private final CUBRIDProvider.CUBRIDGlobalState globalState;

    private CUBRIDDeleteGenerator(CUBRIDProvider.CUBRIDGlobalState globalState) {
        this.globalState = globalState;
    }

    public static SQLQueryAdapter getQuery(CUBRIDProvider.CUBRIDGlobalState globalState) {
        return new CUBRIDDeleteGenerator(globalState).getStatement();
    }

    @Override
    public void buildStatement() {
        CUBRIDSchema.CUBRIDTable table = globalState.getSchema().getRandomTable(t -> !t.isView());
        sb.append("DELETE FROM ");
        sb.append(table.getName());
        if (Randomly.getBoolean()) {
            CUBRIDExpressionGenerator gen = new CUBRIDExpressionGenerator(globalState)
                    .setColumns(new ArrayList<>(table.getColumns()));
            sb.append(" WHERE ");
            sb.append(CUBRIDToStringVisitor.asString(
                    gen.generateExpression(CUBRIDCompositeDataType.getRandomWithType(CUBRIDDataType.BOOLEAN))));
        }
        sb.append(";");
        CUBRIDErrors.addExpressionErrors(errors);
    }
}
