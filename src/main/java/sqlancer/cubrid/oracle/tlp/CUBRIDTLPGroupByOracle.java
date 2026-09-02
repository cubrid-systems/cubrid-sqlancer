package sqlancer.cubrid.oracle.tlp;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import sqlancer.ComparatorHelper;
import sqlancer.cubrid.CUBRIDProvider.CUBRIDGlobalState;
import sqlancer.cubrid.CUBRIDToStringVisitor;

/**
 * {@code GROUP BY} 분할. 그룹 키는 가져올 컬럼과 같게 두고, 술어 세 조각으로 입력 행을 나눈 뒤 그룹 키 집합이 보존되는지 본다.
 *
 * <p>
 * 비교는 반드시 <b>집합</b> 이어야 한다 ({@code UNION}, {@code UNION ALL} 이 아니다). 한 그룹의 행들이 서로 다른 조각으로 흩어지면 그
 * 그룹은 조각마다 한 번씩 나타나므로 개수는 보존되지 않는다. 보존되는 것은 "어떤 그룹 키가 존재하는가" 뿐이다.
 */
public class CUBRIDTLPGroupByOracle extends CUBRIDTLPBase {

    private String generatedQueryString;

    public CUBRIDTLPGroupByOracle(CUBRIDGlobalState state) {
        super(state);
    }

    @Override
    public void check() throws SQLException {
        super.check();
        select.setGroupByExpressions(select.getFetchColumns());
        select.setWhereClause(null);
        String originalQueryString = CUBRIDToStringVisitor.asString(select);
        generatedQueryString = originalQueryString;
        List<String> resultSet = ComparatorHelper.getResultSetFirstColumnAsString(originalQueryString, errors, state);

        select.setWhereClause(predicate);
        String firstQueryString = CUBRIDToStringVisitor.asString(select);
        select.setWhereClause(negatedPredicate);
        String secondQueryString = CUBRIDToStringVisitor.asString(select);
        select.setWhereClause(isNullPredicate);
        String thirdQueryString = CUBRIDToStringVisitor.asString(select);

        List<String> combinedString = new ArrayList<>();
        List<String> secondResultSet = ComparatorHelper.getCombinedResultSetNoDuplicates(firstQueryString,
                secondQueryString, thirdQueryString, combinedString, true, state, errors);
        ComparatorHelper.assumeResultSetsAreEqual(resultSet, secondResultSet, originalQueryString, combinedString,
                state);
    }

    @Override
    public String getLastQueryString() {
        return generatedQueryString;
    }
}
