package sqlancer.cubrid.ast;

import sqlancer.Randomly;
import sqlancer.common.ast.BinaryOperatorNode;
import sqlancer.common.ast.newast.NewUnaryPostfixOperatorNode;
import sqlancer.cubrid.CUBRIDSchema;

public class CUBRIDUnaryPostfixOperation extends NewUnaryPostfixOperatorNode<CUBRIDExpression>
        implements CUBRIDExpression {

    public CUBRIDUnaryPostfixOperation(CUBRIDExpression expr, CUBRIDUnaryPostfixOperator op) {
        super(expr, op);
    }

    public enum CUBRIDUnaryPostfixOperator implements BinaryOperatorNode.Operator {
        IS_NULL("IS NULL") {
            @Override
            public CUBRIDSchema.CUBRIDDataType[] getInputDataTypes() {
                return CUBRIDSchema.CUBRIDDataType.values();
            }
        },
        IS_NOT_NULL("IS NOT NULL") {
            @Override
            public CUBRIDSchema.CUBRIDDataType[] getInputDataTypes() {
                return CUBRIDSchema.CUBRIDDataType.values();
            }
        };

        private final String textRepresentations;

        CUBRIDUnaryPostfixOperator(String text) {
            this.textRepresentations = text;
        }

        public static CUBRIDUnaryPostfixOperator getRandom() {
            return Randomly.fromOptions(values());
        }

        @Override
        public String getTextRepresentation() {
            return textRepresentations;
        }

        public abstract CUBRIDSchema.CUBRIDDataType[] getInputDataTypes();

    }

    public CUBRIDExpression getExpression() {
        return getExpr();
    }

    @Override
    public String getOperatorRepresentation() {
        return this.op.getTextRepresentation();
    }

}
