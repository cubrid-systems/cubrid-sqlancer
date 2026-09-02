package sqlancer.cubrid.ast;

import sqlancer.Randomly;
import sqlancer.common.ast.newast.Join;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDColumn;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDTable;

public class CUBRIDJoin implements CUBRIDExpression, Join<CUBRIDExpression, CUBRIDTable, CUBRIDColumn> {

    private final CUBRIDTableReference leftTable;
    private final CUBRIDTableReference rightTable;
    private final JoinType joinType;
    private CUBRIDExpression onCondition;
    private OuterType outerType;

    public enum JoinType {
        INNER, NATURAL, LEFT, RIGHT;

        public static JoinType getRandom() {
            return Randomly.fromOptions(values());
        }
    }

    public enum OuterType {
        FULL, LEFT, RIGHT;

        public static OuterType getRandom() {
            return Randomly.fromOptions(values());
        }
    }

    public CUBRIDJoin(CUBRIDTableReference leftTable, CUBRIDTableReference rightTable, JoinType joinType,
            CUBRIDExpression whereCondition) {
        this.leftTable = leftTable;
        this.rightTable = rightTable;
        this.joinType = joinType;
        this.onCondition = whereCondition;
    }

    public CUBRIDTableReference getLeftTable() {
        return leftTable;
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

    private void setOuterType(OuterType outerType) {
        this.outerType = outerType;
    }

    public OuterType getOuterType() {
        return outerType;
    }

    public static CUBRIDJoin createRightOuterJoin(CUBRIDTableReference left, CUBRIDTableReference right,
            CUBRIDExpression predicate) {
        return new CUBRIDJoin(left, right, JoinType.RIGHT, predicate);
    }

    public static CUBRIDJoin createLeftOuterJoin(CUBRIDTableReference left, CUBRIDTableReference right,
            CUBRIDExpression predicate) {
        return new CUBRIDJoin(left, right, JoinType.LEFT, predicate);
    }

    public static CUBRIDJoin createInnerJoin(CUBRIDTableReference left, CUBRIDTableReference right,
            CUBRIDExpression predicate) {
        return new CUBRIDJoin(left, right, JoinType.INNER, predicate);
    }

    public static CUBRIDJoin createNaturalJoin(CUBRIDTableReference left, CUBRIDTableReference right,
            OuterType naturalJoinType) {
        CUBRIDJoin join = new CUBRIDJoin(left, right, JoinType.NATURAL, null);
        join.setOuterType(naturalJoinType);
        return join;
    }

    @Override
    public void setOnClause(CUBRIDExpression onClause) {
        onCondition = onClause;
    }

}
