package sqlancer.cubrid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.cubrid.CUBRIDSchema.CUBRIDColumn;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDCompositeDataType;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDDataType;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDTable;
import sqlancer.cubrid.ast.CUBRIDColumnReference;
import sqlancer.cubrid.ast.CUBRIDConstant;
import sqlancer.cubrid.ast.CUBRIDExpression;
import sqlancer.cubrid.ast.CUBRIDJoin;
import sqlancer.cubrid.ast.CUBRIDSelect;
import sqlancer.cubrid.ast.CUBRIDTableReference;

/**
 * SQL 표기에 대한 골든 테스트. 여기서 조용한 회귀가 두 번 났다 — BOOLEAN 자리의 맨 {@code NULL} 과 {@code LEFT  JOIN} 의 이중
 * 공백이다. 둘 다 실행은 되면서 수만 질의를 낭비했고, 표기를 문자열로 고정하지 않으면 다시 그렇게 된다.
 */
class CUBRIDToStringVisitorTest {

    private static CUBRIDTable table(String name, String... columnNames) {
        List<CUBRIDColumn> columns = java.util.Arrays.stream(columnNames)
                .map(c -> new CUBRIDColumn(c, null, new CUBRIDCompositeDataType(CUBRIDDataType.INTEGER, 0)))
                .collect(java.util.stream.Collectors.toList());
        CUBRIDTable t = new CUBRIDTable(name, columns, List.of(), false);
        columns.forEach(c -> c.setTable(t));
        return t;
    }

    private static CUBRIDExpression rawColumn(String text) {
        return new CUBRIDColumnReference(new CUBRIDColumn(text, null, null));
    }

    // ------------------------------------------------------------------ 상수

    @Test
    void booleanConstantsAreIdentityComparisons() {
        assertEquals("(1 = 1)", CUBRIDToStringVisitor.asString(CUBRIDConstant.createBooleanConstant(true)));
        assertEquals("(1 = 0)", CUBRIDToStringVisitor.asString(CUBRIDConstant.createBooleanConstant(false)));
    }

    /**
     * 맨 {@code NULL} 은 CUBRID 에서 술어가 될 수 없다 — {@code (NULL) OR (1 = 1)} 은 구문 오류다. BOOLEAN 자리의 NULL 은
     * UNKNOWN 을 주는 항등 비교여야 한다.
     */
    @Test
    void booleanNullIsNotABareNull() {
        String rendered = CUBRIDToStringVisitor.asString(CUBRIDConstant.createBooleanUnknownConstant());
        assertEquals("(1 = NULL)", rendered);
        assertFalse("NULL".equals(rendered));
    }

    @Test
    void nonBooleanNullStaysBare() {
        assertEquals("NULL", CUBRIDToStringVisitor.asString(CUBRIDConstant.createNullConstant()));
    }

    @Test
    void numericConstantKeepsItsDecimalText() {
        assertEquals("-12.340", CUBRIDToStringVisitor.asString(CUBRIDConstant.createNumericConstant("-12.340")));
    }

    // ------------------------------------------------------------------ 조인

    @Test
    void joinTypesCarryTheOuterKeyword() {
        assertEquals("INNER", CUBRIDJoin.JoinType.INNER.getTextRepresentation());
        assertEquals("LEFT OUTER", CUBRIDJoin.JoinType.LEFT.getTextRepresentation());
        assertEquals("RIGHT OUTER", CUBRIDJoin.JoinType.RIGHT.getTextRepresentation());
        assertEquals("CROSS", CUBRIDJoin.JoinType.CROSS.getTextRepresentation());
    }

    /** CUBRID 는 FULL OUTER JOIN 을 받지 않는다 — {@code before ' OUTER JOIN'} 구문 오류가 난다. */
    @Test
    void thereIsNoFullOuterJoin() {
        for (CUBRIDJoin.JoinType type : CUBRIDJoin.JoinType.values()) {
            assertFalse(type.name().contains("FULL"), "FULL OUTER JOIN 은 CUBRID 에 없다: " + type);
        }
    }

    /** 조인은 이어 붙는다. 쉼표로 나열하면 체인이 아니라 교차곱이 된다. */
    @Test
    void joinsChainWithoutSeparatorAndWithoutDoubleSpace() {
        CUBRIDSelect select = new CUBRIDSelect();
        select.setFetchColumns(List.of(rawColumn("a.c0")));
        select.setFromList(List.of(new CUBRIDTableReference(table("a", "c0"))));
        select.setJoinClauses(List.of(
                new CUBRIDJoin(new CUBRIDTableReference(table("b", "c0")), CUBRIDJoin.JoinType.LEFT,
                        CUBRIDConstant.createBooleanConstant(true)),
                new CUBRIDJoin(new CUBRIDTableReference(table("c", "c0")), CUBRIDJoin.JoinType.INNER,
                        CUBRIDConstant.createBooleanConstant(false))));

        String sql = CUBRIDToStringVisitor.asString(select);
        assertEquals("SELECT a.c0 FROM a LEFT OUTER JOIN b ON (1 = 1) INNER JOIN c ON (1 = 0)", sql);
        assertFalse(sql.contains("  "), "이중 공백이 있다: " + sql);
    }

    /** CROSS JOIN 은 ON 을 받지 않는다. */
    @Test
    void crossJoinHasNoOnClause() {
        CUBRIDSelect select = new CUBRIDSelect();
        select.setFetchColumns(List.of(rawColumn("a.c0")));
        select.setFromList(List.of(new CUBRIDTableReference(table("a", "c0"))));
        select.setJoinClauses(List.of(
                new CUBRIDJoin(new CUBRIDTableReference(table("b", "c0")), CUBRIDJoin.JoinType.CROSS, null)));

        assertEquals("SELECT a.c0 FROM a CROSS JOIN b", CUBRIDToStringVisitor.asString(select));
    }

    // ------------------------------------------------------------------ SELECT

    @Test
    void distinctAndGroupByRender() {
        CUBRIDSelect select = new CUBRIDSelect();
        select.setDistinct(true);
        select.setFetchColumns(List.of(rawColumn("a.c0")));
        select.setFromList(List.of(new CUBRIDTableReference(table("a", "c0"))));
        select.setGroupByExpressions(List.of(rawColumn("a.c0")));
        select.setWhereClause(CUBRIDConstant.createBooleanConstant(true));

        assertEquals("SELECT DISTINCT a.c0 FROM a WHERE (1 = 1) GROUP BY a.c0",
                CUBRIDToStringVisitor.asString(select));
    }
}
