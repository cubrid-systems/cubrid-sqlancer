package sqlancer.cubrid.gen;

import java.util.List;

import sqlancer.Randomly;
import sqlancer.common.gen.AbstractUpdateGenerator;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.cubrid.CUBRIDErrors;
import sqlancer.cubrid.CUBRIDProvider;
import sqlancer.cubrid.CUBRIDSchema;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDColumn;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDCompositeDataType;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDDataType;
import sqlancer.cubrid.CUBRIDToStringVisitor;
import sqlancer.cubrid.ast.CUBRIDExpression;

public final class CUBRIDUpdateGenerator extends AbstractUpdateGenerator<CUBRIDColumn> {

    private final CUBRIDProvider.CUBRIDGlobalState globalState;
    private CUBRIDExpressionGenerator gen;

    private CUBRIDUpdateGenerator(CUBRIDProvider.CUBRIDGlobalState globalState) {
        this.globalState = globalState;
    }

    public static SQLQueryAdapter getQuery(CUBRIDProvider.CUBRIDGlobalState globalState) {
        return new CUBRIDUpdateGenerator(globalState).getStatement();
    }

    @Override
    public void buildStatement() {
        CUBRIDSchema.CUBRIDTable table = globalState.getSchema().getRandomTable(t -> !t.isView());
        List<CUBRIDSchema.CUBRIDColumn> columns = table.getRandomNonEmptyColumnSubset();
        gen = new CUBRIDExpressionGenerator(globalState).setColumns(table.getColumns());
        sb.append("UPDATE ");
        sb.append(table.getName());
        sb.append(" SET ");
        updateColumns(columns);
        // SET 절의 상수만으로도 타입 범위 초과가 발생한다. WHERE 유무와 무관하게 등록해야 한다.
        CUBRIDErrors.addExpressionErrors(errors);
        if (Randomly.getBooleanWithSmallProbability()) {
            appendWhereClause(CUBRIDToStringVisitor.asString(
                    gen.generateExpression(CUBRIDCompositeDataType.getRandomWithType(CUBRIDDataType.BOOLEAN))));
        }
    }

    @Override
    protected void updateValue(CUBRIDColumn column) {
        CUBRIDExpression expr;
        expr = gen.generateConstant(column.getType());
        sb.append(CUBRIDToStringVisitor.asString(expr));
    }

}
