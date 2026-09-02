package sqlancer.cubrid.ast;

/** {@code (x) [NOT] BETWEEN (lo) AND (hi)} — 범위 술어는 옵티마이저가 자주 재작성하는 형태다. */
public class CUBRIDBetweenOperation implements CUBRIDExpression {

    private final CUBRIDExpression expr;
    private final CUBRIDExpression left;
    private final CUBRIDExpression right;
    private final boolean not;

    public CUBRIDBetweenOperation(CUBRIDExpression expr, CUBRIDExpression left, CUBRIDExpression right, boolean not) {
        this.expr = expr;
        this.left = left;
        this.right = right;
        this.not = not;
    }

    public CUBRIDExpression getExpr() {
        return expr;
    }

    public CUBRIDExpression getLeft() {
        return left;
    }

    public CUBRIDExpression getRight() {
        return right;
    }

    public boolean isNot() {
        return not;
    }
}
