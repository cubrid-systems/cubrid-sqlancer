package sqlancer.cubrid.ast;

import sqlancer.common.ast.BinaryOperatorNode;
import sqlancer.common.ast.newast.NewUnaryPrefixOperatorNode;
import sqlancer.cubrid.CUBRIDSchema;

public class CUBRIDUnaryPrefixOperation extends NewUnaryPrefixOperatorNode<CUBRIDExpression>
        implements CUBRIDExpression {

    public CUBRIDUnaryPrefixOperation(CUBRIDUnaryPrefixOperator operation, CUBRIDExpression expression) {
        super(expression, operation);
    }

    @Override
    public String getOperatorRepresentation() {
        return this.op.getTextRepresentation();
    }

    public enum CUBRIDUnaryPrefixOperator implements BinaryOperatorNode.Operator {
        NOT("NOT", CUBRIDSchema.CUBRIDDataType.BOOLEAN, CUBRIDSchema.CUBRIDDataType.INTEGER) {
            @Override
            public CUBRIDSchema.CUBRIDDataType getExpressionType() {
                return CUBRIDSchema.CUBRIDDataType.BOOLEAN;
            }
        },

        UNARY_PLUS("+", CUBRIDSchema.CUBRIDDataType.INTEGER) {
            @Override
            public CUBRIDSchema.CUBRIDDataType getExpressionType() {
                return CUBRIDSchema.CUBRIDDataType.INTEGER;
            }
        },
        UNARY_MINUS("-", CUBRIDSchema.CUBRIDDataType.INTEGER) {
            @Override
            public CUBRIDSchema.CUBRIDDataType getExpressionType() {
                return CUBRIDSchema.CUBRIDDataType.INTEGER;
            }
        };

        private String textRepresentation;

        CUBRIDUnaryPrefixOperator(String textRepresentation, CUBRIDSchema.CUBRIDDataType... dataTypes) {
            this.textRepresentation = textRepresentation;
        }

        public abstract CUBRIDSchema.CUBRIDDataType getExpressionType();

        @Override
        public String getTextRepresentation() {
            return this.textRepresentation;
        }
    }

}
