package sqlancer.cubrid.oracle.tlp;

import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

import sqlancer.Randomly;
import sqlancer.common.gen.ExpressionGenerator;
import sqlancer.common.oracle.TernaryLogicPartitioningOracleBase;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.oracle.TestOracleUtils;
import sqlancer.common.schema.AbstractTables;
import sqlancer.cubrid.CUBRIDErrors;
import sqlancer.cubrid.CUBRIDProvider.CUBRIDGlobalState;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDColumn;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDTable;
import sqlancer.cubrid.ast.CUBRIDColumnReference;
import sqlancer.cubrid.ast.CUBRIDExpression;
import sqlancer.cubrid.ast.CUBRIDSelect;
import sqlancer.cubrid.gen.CUBRIDExpressionGenerator;

/**
 * TLP 계열 오라클의 공통 준비 단계. 한 술어 {@code p} 로부터 {@code p} · {@code NOT p} · {@code p IS NULL} 세 조각을 만들고,
 * 그 세 조각으로 원본 질의를 분할한다.
 *
 * <p>
 * NoREC 과 달리 여기서는 행을 세지 않고 결과 자체를 비교하므로, GROUP BY 와 DISTINCT 처럼 행 수를 바꾸는 절을 쓸 수 있다. 대신 비교가
 * 집합 의미여야 한다 — 그 이유는 각 하위 오라클에 적었다.
 */
public abstract class CUBRIDTLPBase extends TernaryLogicPartitioningOracleBase<CUBRIDExpression, CUBRIDGlobalState>
        implements TestOracle<CUBRIDGlobalState> {

    protected CUBRIDExpressionGenerator gen;
    protected CUBRIDSelect select;
    protected AbstractTables<CUBRIDTable, CUBRIDColumn> targetTables;

    protected CUBRIDTLPBase(CUBRIDGlobalState state) {
        super(state);
        CUBRIDErrors.addExpressionErrors(errors);
    }

    @Override
    public void check() throws SQLException {
        gen = new CUBRIDExpressionGenerator(state);
        targetTables = TestOracleUtils.getRandomTableNonEmptyTables(state.getSchema());
        gen.setTablesAndColumns(targetTables);

        select = new CUBRIDSelect();
        // 조인은 tables 에서 테이블을 꺼내 쓴다 — FROM 목록은 그 뒤에 남은 것으로 만들어야 한다.
        select.setJoinClauses(gen.getRandomJoinClauses());
        select.setFromList(gen.getTableRefs());
        select.setFetchColumns(generateFetchColumns());
        select.setWhereClause(null);

        initializeTernaryPredicateVariants();
    }

    /**
     * 가져올 컬럼. 식이 아니라 컬럼이어야 한다 — GROUP BY 오라클이 이 목록을 그대로 그룹 키로 쓰기 때문이다.
     */
    protected List<CUBRIDExpression> generateFetchColumns() {
        return Randomly.nonEmptySubset(targetTables.getColumns()).stream()
                .map(c -> (CUBRIDExpression) new CUBRIDColumnReference(c)).collect(Collectors.toList());
    }

    @Override
    protected ExpressionGenerator<CUBRIDExpression> getGen() {
        return gen;
    }
}
