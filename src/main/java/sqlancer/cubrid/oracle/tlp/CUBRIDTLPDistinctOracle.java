package sqlancer.cubrid.oracle.tlp;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import sqlancer.ComparatorHelper;
import sqlancer.cubrid.CUBRIDProvider.CUBRIDGlobalState;
import sqlancer.cubrid.CUBRIDToStringVisitor;

/**
 * {@code DISTINCT} 분할. 테이블 전체의 서로 다른 행들은 각 조각의 서로 다른 행들을 합집합한 것과 같아야 한다.
 *
 * <p>
 * 여기서도 비교는 <b>집합</b> 이다. 같은 행이 여러 조각에 나타날 수는 없지만, 원본이 이미 중복을 제거한 결과이므로 조각들을
 * {@code UNION ALL} 로 이으면 조각 간 중복이 남을 수 있다.
 */
public class CUBRIDTLPDistinctOracle extends CUBRIDTLPBase {

    private String generatedQueryString;

    public CUBRIDTLPDistinctOracle(CUBRIDGlobalState state) {
        super(state);
    }

    @Override
    public void check() throws SQLException {
        super.check();
        select.setDistinct(true);
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
