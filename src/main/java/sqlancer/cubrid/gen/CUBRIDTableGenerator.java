package sqlancer.cubrid.gen;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import sqlancer.Randomly;
import sqlancer.common.gen.AbstractTableGenerator;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.cubrid.CUBRIDErrors;
import sqlancer.cubrid.CUBRIDProvider;
import sqlancer.cubrid.CUBRIDSchema;

public class CUBRIDTableGenerator extends AbstractTableGenerator<CUBRIDSchema.CUBRIDColumn> {

    private CUBRIDProvider.CUBRIDGlobalState globalState;
    private String tableName;

    public CUBRIDTableGenerator() {
        this.canAffectSchema = true;
    }

    public SQLQueryAdapter getQuery(CUBRIDProvider.CUBRIDGlobalState globalState, @Nullable String tableName) {
        this.globalState = globalState;
        this.tableName = tableName;
        return getStatement();
    }

    @Override
    public void buildStatement() {
        String name = tableName;
        if (name == null) {
            name = globalState.getSchema().getFreeTableName();
        }
        appendCreateTable(name, Randomly.getBoolean());
        appendColumnDefinitions(getNewColumns());
        sb.append(";");
        CUBRIDErrors.addExpressionErrors(errors);
        errors.add("already exists");
    }

    @Override
    protected void appendColumnDefinition(CUBRIDSchema.CUBRIDColumn column) {
        sb.append(column.getName());
        sb.append(" ");
        // CHAR/VARCHAR 만 길이를 갖는다. 나머지에 길이를 붙이면 CUBRID 가 거부한다.
        sb.append(column.getType().toSqlString());
    }

    private static List<CUBRIDSchema.CUBRIDColumn> getNewColumns() {
        List<CUBRIDSchema.CUBRIDColumn> columns = new ArrayList<>();
        for (int i = 0; i < Randomly.smallNumber() + 1; i++) {
            String columnName = String.format("c%d", i);
            // BOOLEAN 은 CUBRID 의 컬럼 타입이 아니므로 getRandomStorable() 이 절대 돌려주지 않는다.
            CUBRIDSchema.CUBRIDCompositeDataType columnType = CUBRIDSchema.CUBRIDCompositeDataType.getRandomStorable();
            columns.add(new CUBRIDSchema.CUBRIDColumn(columnName, null, columnType));
        }
        return columns;
    }
}
