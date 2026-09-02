package sqlancer.cubrid.ast;

import sqlancer.Randomly;
import sqlancer.common.ast.newast.Join;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDColumn;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDTable;

/**
 * 조인 하나. 왼쪽은 갖지 않는다 — 왼쪽은 {@code FROM} 목록이거나 앞선 조인의 결과이고, 그래야 {@code a JOIN b ON .. JOIN c ON ..}
 * 처럼 이어 붙일 수 있다.
 */
public class CUBRIDJoin implements CUBRIDExpression, Join<CUBRIDExpression, CUBRIDTable, CUBRIDColumn> {

    private final CUBRIDTableReference rightTable;
    private final JoinType joinType;
    private CUBRIDExpression onCondition;

    /**
     * CUBRID 가 받는 조인 종류. {@code FULL OUTER JOIN} 은 없다 — {@code before ' OUTER JOIN'} 구문 오류가 난다.
     * {@code CROSS} 는 {@code ON} 을 받지 않는다.
     */
    public enum JoinType {
        INNER("INNER"), LEFT("LEFT OUTER"), RIGHT("RIGHT OUTER"), CROSS("CROSS");

        private final String textRepresentation;

        JoinType(String textRepresentation) {
            this.textRepresentation = textRepresentation;
        }

        public String getTextRepresentation() {
            return textRepresentation;
        }

        public static JoinType getRandom() {
            return Randomly.fromOptions(values());
        }
    }

    public CUBRIDJoin(CUBRIDTableReference rightTable, JoinType joinType, CUBRIDExpression onCondition) {
        this.rightTable = rightTable;
        this.joinType = joinType;
        this.onCondition = onCondition;
    }

    public CUBRIDTableReference getRightTable() {
        return rightTable;
    }

    public JoinType getJoinType() {
        return joinType;
    }

    public CUBRIDExpression getOnCondition() {
        return onCondition;
    }

    @Override
    public void setOnClause(CUBRIDExpression onClause) {
        onCondition = onClause;
    }
}
