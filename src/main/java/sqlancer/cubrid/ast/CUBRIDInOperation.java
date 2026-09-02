package sqlancer.cubrid.ast;

import java.util.List;

/** {@code (x) [NOT] IN (a, b, c)} — IN 리스트는 OR 사슬로 풀리기도 하고 인덱스 스캔으로 접히기도 한다. */
public class CUBRIDInOperation implements CUBRIDExpression {

    private final CUBRIDExpression expr;
    private final List<CUBRIDExpression> listElements;
    private final boolean not;

    public CUBRIDInOperation(CUBRIDExpression expr, List<CUBRIDExpression> listElements, boolean not) {
        this.expr = expr;
        this.listElements = listElements;
        this.not = not;
    }

    public CUBRIDExpression getExpr() {
        return expr;
    }

    public List<CUBRIDExpression> getListElements() {
        return listElements;
    }

    public boolean isNot() {
        return not;
    }
}
