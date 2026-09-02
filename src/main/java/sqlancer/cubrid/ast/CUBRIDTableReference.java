package sqlancer.cubrid.ast;

import sqlancer.common.ast.newast.TableReferenceNode;
import sqlancer.cubrid.CUBRIDSchema;

public class CUBRIDTableReference extends TableReferenceNode<CUBRIDExpression, CUBRIDSchema.CUBRIDTable>
        implements CUBRIDExpression {
    public CUBRIDTableReference(CUBRIDSchema.CUBRIDTable table) {
        super(table);
    }
}
