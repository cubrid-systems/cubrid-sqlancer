package sqlancer.cubrid.gen;

import java.util.List;

import sqlancer.common.gen.AbstractInsertGenerator;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.cubrid.CUBRIDErrors;
import sqlancer.cubrid.CUBRIDProvider;
import sqlancer.cubrid.CUBRIDSchema;
import sqlancer.cubrid.CUBRIDToStringVisitor;
import sqlancer.cubrid.ast.CUBRIDExpression;

public class CUBRIDInsertGenerator extends AbstractInsertGenerator<CUBRIDSchema.CUBRIDColumn> {

    private final CUBRIDProvider.CUBRIDGlobalState globalState;

    public CUBRIDInsertGenerator(CUBRIDProvider.CUBRIDGlobalState globalState) {
        this.globalState = globalState;
    }

    public static SQLQueryAdapter getQuery(CUBRIDProvider.CUBRIDGlobalState globalState) {
        return new CUBRIDInsertGenerator(globalState).getStatement();
    }

    @Override
    public void buildStatement() {
        CUBRIDSchema.CUBRIDTable table = globalState.getSchema().getRandomTable(t -> !t.isView());
        List<CUBRIDSchema.CUBRIDColumn> columns = table.getRandomNonEmptyColumnSubset();
        buildInsertInto(table.getName(), columns);
        // 무작위 상수는 컬럼 타입 범위를 넘거나 길이를 초과할 수 있다 — 버그가 아니다.
        CUBRIDErrors.addInsertErrors(errors);
    }

    @Override
    protected void insertValue(CUBRIDSchema.CUBRIDColumn column) {
        CUBRIDExpression expression = new CUBRIDExpressionGenerator(globalState).generateConstant(column.getType());
        String s = CUBRIDToStringVisitor.asString(expression);
        sb.append(s);
    }

}
