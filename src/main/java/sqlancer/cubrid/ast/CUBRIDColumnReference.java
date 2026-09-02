package sqlancer.cubrid.ast;

import sqlancer.common.ast.newast.ColumnReferenceNode;
import sqlancer.cubrid.CUBRIDSchema;

public class CUBRIDColumnReference extends ColumnReferenceNode<CUBRIDExpression, CUBRIDSchema.CUBRIDColumn>
        implements CUBRIDExpression {

    public CUBRIDColumnReference(CUBRIDSchema.CUBRIDColumn column) {
        super(column);
    }
}
