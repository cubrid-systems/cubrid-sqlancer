package sqlancer.cubrid.ast;

import sqlancer.common.ast.BinaryOperatorNode.Operator;
import sqlancer.common.ast.newast.NewBinaryOperatorNode;

public class CUBRIDBinaryOperation extends NewBinaryOperatorNode<CUBRIDExpression> implements CUBRIDExpression {
    public CUBRIDBinaryOperation(CUBRIDExpression left, CUBRIDExpression right, Operator op) {
        super(left, right, op);
    }
}
