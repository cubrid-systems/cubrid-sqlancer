package sqlancer.cubrid.oracle.tlp;

import java.sql.SQLException;
import java.util.List;

import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.query.SQLancerResultSet;
import sqlancer.cubrid.CUBRIDProvider.CUBRIDGlobalState;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDColumn;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDCompositeDataType;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDDataType;
import sqlancer.cubrid.CUBRIDToStringVisitor;
import sqlancer.cubrid.ast.CUBRIDColumnReference;
import sqlancer.cubrid.ast.CUBRIDExpression;

/**
 * 집계 분할. 그룹 키와 달리 집계값은 입력을 나누면 보존되지 않으므로, 조각의 부분값을 <b>다시 집계</b>해서 원본과 맞춘다.
 *
 * <pre>
 * SELECT AGG(e) FROM t
 *   =
 * SELECT AGG'(aggr) FROM (
 *     SELECT AGG(e) AS aggr FROM t WHERE p
 *     UNION ALL SELECT AGG(e) AS aggr FROM t WHERE NOT p
 *     UNION ALL SELECT AGG(e) AS aggr FROM t WHERE p IS NULL
 * ) AS res
 * </pre>
 *
 * <p>
 * 여기서는 {@code UNION ALL} 이어야 한다. 합칠 대상이 그룹 키가 아니라 부분값이고, 같은 부분값이 두 조각에서 나오면 둘 다 세어야 한다.
 *
 * <p>
 * 쓸 수 있는 집계는 결합 규칙이 자기 자신인 것뿐이다 — {@code MIN}, {@code MAX}, {@code SUM}. {@code COUNT} 는 부분
 * 개수를 더해야 하므로 밖에서 {@code SUM} 을 쓴다. {@code AVG} 는 이 방식으로 성립하지 않는다 (부분평균의 평균은 전체평균이 아니다).
 */
public class CUBRIDTLPAggregateOracle extends CUBRIDTLPBase {

    private String generatedQueryString;

    public CUBRIDTLPAggregateOracle(CUBRIDGlobalState state) {
        super(state);
    }

    @Override
    public void check() throws SQLException {
        super.check();

        String function = Randomly.fromOptions("MIN", "MAX", "SUM", "COUNT");
        String aggregate;
        if ("COUNT".equals(function)) {
            aggregate = "COUNT(*)";
        } else {
            CUBRIDDataType argType = Randomly.fromOptions(CUBRIDDataType.INTEGER, CUBRIDDataType.BIGINT,
                    CUBRIDDataType.DOUBLE, CUBRIDDataType.NUMERIC);
            String argument = CUBRIDToStringVisitor
                    .asString(gen.generateExpression(CUBRIDCompositeDataType.getRandomWithType(argType)));
            aggregate = function + "(" + argument + ")";
        }
        // 부분 개수는 더해야 한다. 밖에서 COUNT 를 다시 쓰면 조각 수(3)를 센다.
        String outerFunction = "COUNT".equals(function) ? "SUM" : function;

        String originalQuery = queryWith(aggregate, null);
        generatedQueryString = originalQuery;
        String metamorphicQuery = "SELECT " + outerFunction + "(aggr) FROM (" + queryWith(aggregate, predicate)
                + " UNION ALL " + queryWith(aggregate, negatedPredicate) + " UNION ALL "
                + queryWith(aggregate, isNullPredicate) + ") AS res";

        String originalResult = firstValueOf(originalQuery);
        String metamorphicResult = firstValueOf(metamorphicQuery);

        state.getState().getLocalState().log(
                "--" + originalQuery + "\n--" + metamorphicQuery + "\n-- " + originalResult + "\n-- "
                        + metamorphicResult);

        // 한쪽만 NULL 이면 그것 자체가 불일치다. SUM 은 빈 입력에 NULL 을 돌려주므로 실제로 생기는 경우다.
        boolean equal;
        if (originalResult == null || metamorphicResult == null) {
            equal = originalResult == null && metamorphicResult == null;
        } else {
            equal = originalResult.contentEquals(metamorphicResult);
        }
        if (!equal && !ComparatorHelper.isEqualDouble(originalResult, metamorphicResult)) {
            throw new AssertionError(
                    originalQuery + " gave " + originalResult + ", " + metamorphicQuery + " gave " + metamorphicResult);
        }
    }

    /** {@code select} 의 FROM 과 조인을 그대로 쓰고 가져올 컬럼과 WHERE 만 바꾼다. */
    private String queryWith(String aggregate, CUBRIDExpression whereClause) {
        String alias = whereClause == null ? "" : " AS aggr";
        select.setFetchColumns(
                List.of(new CUBRIDColumnReference(new CUBRIDColumn(aggregate + alias, null, null))));
        select.setWhereClause(whereClause);
        return CUBRIDToStringVisitor.asString(select);
    }

    private String firstValueOf(String queryString) throws SQLException {
        SQLQueryAdapter q = new SQLQueryAdapter(queryString, errors);
        try (SQLancerResultSet result = q.executeAndGet(state)) {
            if (result == null) {
                throw new IgnoreMeException();
            }
            // 커서를 옮기지 않고 읽으면 "Invalid cursor position." 이 난다. 집계 질의는 언제나 한 행이다.
            if (!result.next()) {
                throw new IgnoreMeException();
            }
            return result.getString(1);
        } catch (IgnoreMeException e) {
            throw e;
        } catch (Exception e) {
            throw new IgnoreMeException();
        }
    }

    @Override
    public String getLastQueryString() {
        return generatedQueryString;
    }
}
