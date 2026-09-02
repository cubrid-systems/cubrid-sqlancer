package sqlancer.cubrid;

import sqlancer.common.ast.newast.NewToStringVisitor;
import sqlancer.cubrid.ast.CUBRIDBetweenOperation;
import sqlancer.cubrid.ast.CUBRIDConstant;
import sqlancer.cubrid.ast.CUBRIDInOperation;
import sqlancer.cubrid.ast.CUBRIDExpression;
import sqlancer.cubrid.ast.CUBRIDJoin;
import sqlancer.cubrid.ast.CUBRIDSelect;

public class CUBRIDToStringVisitor extends NewToStringVisitor<CUBRIDExpression> {

    @Override
    public void visitSpecific(CUBRIDExpression expr) {
        if (expr instanceof CUBRIDConstant) {
            visit((CUBRIDConstant) expr);
        } else if (expr instanceof CUBRIDSelect) {
            visit((CUBRIDSelect) expr);
        } else if (expr instanceof CUBRIDJoin) {
            visit((CUBRIDJoin) expr);
        } else if (expr instanceof CUBRIDBetweenOperation) {
            visit((CUBRIDBetweenOperation) expr);
        } else if (expr instanceof CUBRIDInOperation) {
            visit((CUBRIDInOperation) expr);
        } else {
            throw new AssertionError(expr.getClass());
        }
    }

    public static String asString(CUBRIDExpression expr) {
        CUBRIDToStringVisitor visitor = new CUBRIDToStringVisitor();
        visitor.visit(expr);
        return visitor.get();
    }

    private void visit(CUBRIDJoin join) {
        visit((CUBRIDExpression) join.getLeftTable());
        sb.append(" ");
        sb.append(join.getJoinType());
        sb.append(" ");
        if (join.getOuterType() != null) {
            sb.append(join.getOuterType());
        }
        sb.append(" JOIN ");
        visit((CUBRIDExpression) join.getRightTable());
        if (join.getOnCondition() != null) {
            sb.append(" ON ");
            visit(join.getOnCondition());
        }
    }

    private void visit(CUBRIDBetweenOperation op) {
        sb.append("(");
        visit(op.getExpr());
        sb.append(op.isNot() ? " NOT BETWEEN " : " BETWEEN ");
        visit(op.getLeft());
        sb.append(" AND ");
        visit(op.getRight());
        sb.append(")");
    }

    private void visit(CUBRIDInOperation op) {
        sb.append("(");
        visit(op.getExpr());
        sb.append(op.isNot() ? " NOT IN (" : " IN (");
        for (int i = 0; i < op.getListElements().size(); i++) {
            if (i != 0) {
                sb.append(", ");
            }
            visit(op.getListElements().get(i));
        }
        sb.append("))");
    }

    private void visit(CUBRIDConstant constant) {
        sb.append(constant.toString());
    }

    private void visit(CUBRIDSelect select) {
        sb.append("SELECT ");
        if (select.isDistinct()) {
            sb.append("DISTINCT ");
        }
        visit(select.getFetchColumns());
        sb.append(" FROM ");
        visit(select.getFromList());
        if (!select.getFromList().isEmpty() && !select.getJoinList().isEmpty()) {
            sb.append(", ");
        }
        if (!select.getJoinList().isEmpty()) {
            visit(select.getJoinList());
        }
        if (select.getWhereClause() != null) {
            sb.append(" WHERE ");
            visit(select.getWhereClause());
        }
        if (!select.getGroupByExpressions().isEmpty()) {
            sb.append(" GROUP BY ");
            visit(select.getGroupByExpressions());
        }
        if (select.getHavingClause() != null) {
            sb.append(" HAVING ");
            visit(select.getHavingClause());
        }
        if (!select.getOrderByClauses().isEmpty()) {
            sb.append(" ORDER BY ");
            visit(select.getOrderByClauses());
        }
        // CUBRID 는 OFFSET 을 LIMIT 의 첫 인자로 받는다: LIMIT <offset>, <count>
        if (select.getLimitClause() != null) {
            sb.append(" LIMIT ");
            if (select.getOffsetClause() != null) {
                visit(select.getOffsetClause());
                sb.append(", ");
            }
            visit(select.getLimitClause());
        }
    }
}
