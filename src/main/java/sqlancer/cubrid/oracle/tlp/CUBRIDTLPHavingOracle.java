package sqlancer.cubrid.oracle.tlp;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import sqlancer.ComparatorHelper;
import sqlancer.Randomly;
import sqlancer.cubrid.CUBRIDProvider.CUBRIDGlobalState;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDColumn;
import sqlancer.cubrid.CUBRIDToStringVisitor;
import sqlancer.cubrid.ast.CUBRIDBinaryOperation;
import sqlancer.cubrid.ast.CUBRIDColumnReference;
import sqlancer.cubrid.ast.CUBRIDConstant;
import sqlancer.cubrid.ast.CUBRIDExpression;
import sqlancer.cubrid.gen.CUBRIDExpressionGenerator.CUBRIDBinaryComparisonOperator;

/**
 * {@code HAVING} 분할. 그룹은 {@code GROUP BY} 로 이미 정해지고 {@code HAVING} 은 그 <b>그룹들</b>을 세 갈래로 나눈다 —
 * {@code p} · {@code NOT p} · {@code p IS NULL}. 3치 논리에서 이 셋은 망라적이므로 모든 그룹이 정확히 한 분기에 들어간다.
 *
 * <p>
 * 그래서 여기서는 {@code UNION ALL} 이다. GROUP BY 오라클과 달리 그룹이 여러 분기에 쪼개지지 않으니 중복이 생길 수 없다.
 *
 * <p>
 * 술어는 집계를 담아야 의미가 있다. 그룹 컬럼만 참조하는 {@code HAVING} 은 {@code WHERE} 와 다를 바 없어 그룹화 경로를 누르지 못한다.
 */
public class CUBRIDTLPHavingOracle extends CUBRIDTLPBase {

    private String generatedQueryString;

    public CUBRIDTLPHavingOracle(CUBRIDGlobalState state) {
        super(state);
    }

    @Override
    public void check() throws SQLException {
        super.check();
        select.setWhereClause(null);
        select.setGroupByExpressions(select.getFetchColumns());

        select.setHavingClause(null);
        String originalQueryString = CUBRIDToStringVisitor.asString(select);
        generatedQueryString = originalQueryString;
        List<String> resultSet = ComparatorHelper.getResultSetFirstColumnAsString(originalQueryString, errors, state);

        CUBRIDExpression having = generateHavingClause();
        select.setHavingClause(having);
        String firstQueryString = CUBRIDToStringVisitor.asString(select);
        select.setHavingClause(gen.negatePredicate(having));
        String secondQueryString = CUBRIDToStringVisitor.asString(select);
        select.setHavingClause(gen.isNull(having));
        String thirdQueryString = CUBRIDToStringVisitor.asString(select);

        List<String> combinedString = new ArrayList<>();
        List<String> secondResultSet = ComparatorHelper.getCombinedResultSet(firstQueryString, secondQueryString,
                thirdQueryString, combinedString, true, state, errors);
        ComparatorHelper.assumeResultSetsAreEqual(resultSet, secondResultSet, originalQueryString, combinedString,
                state);
    }

    /**
     * {@code <집계> <비교> <상수>}. 집계 함수 이름은 식이 아니므로 원시 텍스트를 담은 컬럼으로 감싼다 — provider 가 {@code COUNT(*)} 를
     * 다루는 방식과 같다.
     */
    private CUBRIDExpression generateHavingClause() {
        // SUM 은 숫자만 받고, MIN/MAX 는 컬럼의 타입을 돌려주므로 정수 상수와 비교하려면 그쪽도 숫자여야 한다 —
        // SUM(<date>) 은 "Cannot coerce ... to type double", MIN(<date>) > 0 은 "Cannot coerce 0 to type date" 다.
        // COUNT 는 어떤 타입이든 정수를 돌려주므로 제한이 없다.
        List<CUBRIDColumn> numeric = targetTables.getColumns().stream()
                .filter(c -> c.getType() != null && c.getType().getType().isNumeric())
                .collect(Collectors.toList());
        String aggregate;
        if (numeric.isEmpty() || Randomly.getBooleanWithRatherLowProbability()) {
            aggregate = "COUNT(*)";
        } else if (Randomly.getBooleanWithRatherLowProbability()) {
            CUBRIDColumn column = Randomly.fromList(targetTables.getColumns());
            aggregate = "COUNT(" + CUBRIDToStringVisitor.asString(new CUBRIDColumnReference(column)) + ")";
        } else {
            CUBRIDColumn column = Randomly.fromList(numeric);
            String argument = CUBRIDToStringVisitor.asString(new CUBRIDColumnReference(column));
            aggregate = Randomly.fromOptions("MIN", "MAX", "SUM") + "(" + argument + ")";
        }
        CUBRIDExpression left = new CUBRIDColumnReference(new CUBRIDColumn(aggregate, null, null));
        CUBRIDExpression right = CUBRIDConstant
                .createIntConstant(state.getRandomly().getInteger(-4, 8));
        return new CUBRIDBinaryOperation(left, right, CUBRIDBinaryComparisonOperator.getRandom());
    }

    @Override
    public String getLastQueryString() {
        return generatedQueryString;
    }
}
