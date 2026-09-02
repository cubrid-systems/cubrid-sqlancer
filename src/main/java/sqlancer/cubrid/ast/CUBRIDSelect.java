package sqlancer.cubrid.ast;

import java.util.List;
import java.util.stream.Collectors;

import sqlancer.common.ast.SelectBase;
import sqlancer.common.ast.newast.Select;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDColumn;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDTable;
import sqlancer.cubrid.CUBRIDToStringVisitor;

public class CUBRIDSelect extends SelectBase<CUBRIDExpression>
        implements CUBRIDExpression, Select<CUBRIDJoin, CUBRIDExpression, CUBRIDTable, CUBRIDColumn> {

    private boolean isDistinct;

    public void setDistinct(boolean isDistinct) {
        this.isDistinct = isDistinct;
    }

    public boolean isDistinct() {
        return isDistinct;
    }

    @Override
    public void setJoinClauses(List<CUBRIDJoin> joinStatements) {
        List<CUBRIDExpression> expressions = joinStatements.stream().map(e -> (CUBRIDExpression) e)
                .collect(Collectors.toList());
        setJoinList(expressions);
    }

    @Override
    public List<CUBRIDJoin> getJoinClauses() {
        return getJoinList().stream().map(e -> (CUBRIDJoin) e).collect(Collectors.toList());
    }

    @Override
    public String asString() {
        return CUBRIDToStringVisitor.asString(this);
    }
}
