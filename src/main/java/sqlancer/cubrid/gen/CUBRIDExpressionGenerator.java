package sqlancer.cubrid.gen;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import sqlancer.Randomly;
import sqlancer.common.ast.BinaryOperatorNode;
import sqlancer.common.gen.NoRECGenerator;
import sqlancer.common.gen.TLPWhereGenerator;
import sqlancer.common.gen.TypedExpressionGenerator;
import sqlancer.common.schema.AbstractTables;
import sqlancer.cubrid.CUBRIDProvider;
import sqlancer.cubrid.CUBRIDSchema;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDColumn;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDCompositeDataType;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDDataType;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDTable;
import sqlancer.cubrid.CUBRIDToStringVisitor;
import sqlancer.cubrid.ast.CUBRIDBetweenOperation;
import sqlancer.cubrid.ast.CUBRIDBinaryOperation;
import sqlancer.cubrid.ast.CUBRIDColumnReference;
import sqlancer.cubrid.ast.CUBRIDConstant;
import sqlancer.cubrid.ast.CUBRIDExpression;
import sqlancer.cubrid.ast.CUBRIDInOperation;
import sqlancer.cubrid.ast.CUBRIDJoin;
import sqlancer.cubrid.ast.CUBRIDSelect;
import sqlancer.cubrid.ast.CUBRIDTableReference;
import sqlancer.cubrid.ast.CUBRIDUnaryPostfixOperation;
import sqlancer.cubrid.ast.CUBRIDUnaryPrefixOperation;

public final class CUBRIDExpressionGenerator
        extends TypedExpressionGenerator<CUBRIDExpression, CUBRIDColumn, CUBRIDCompositeDataType>
        implements NoRECGenerator<CUBRIDSelect, CUBRIDJoin, CUBRIDExpression, CUBRIDTable, CUBRIDColumn>,
        TLPWhereGenerator<CUBRIDSelect, CUBRIDJoin, CUBRIDExpression, CUBRIDTable, CUBRIDColumn> {

    private final CUBRIDProvider.CUBRIDGlobalState globalState;
    private List<CUBRIDTable> tables;

    public CUBRIDExpressionGenerator(CUBRIDProvider.CUBRIDGlobalState globalState) {
        this.globalState = globalState;
    }

    // ---------------------------------------------------------------- 술어

    @Override
    public CUBRIDExpression generatePredicate() {
        return generateExpression(CUBRIDCompositeDataType.getRandomWithType(CUBRIDDataType.BOOLEAN));
    }

    @Override
    public CUBRIDExpression negatePredicate(CUBRIDExpression predicate) {
        return new CUBRIDUnaryPrefixOperation(CUBRIDUnaryPrefixOperation.CUBRIDUnaryPrefixOperator.NOT, predicate);
    }

    @Override
    public CUBRIDExpression isNull(CUBRIDExpression expr) {
        return new CUBRIDUnaryPostfixOperation(expr, CUBRIDUnaryPostfixOperation.CUBRIDUnaryPostfixOperator.IS_NULL);
    }

    // ------------------------------------------------------------- 식 생성

    @Override
    protected CUBRIDExpression generateExpression(CUBRIDCompositeDataType type, int depth) {
        if (depth >= globalState.getOptions().getMaxExpressionDepth()
                || Randomly.getBooleanWithSmallProbability()) {
            return generateLeafNode(type);
        }

        if (type.getType() == CUBRIDDataType.BOOLEAN) {
            // CUBRID 에는 저장 가능한 boolean 이 없다. 술어는 비교·논리 결합·범위·목록으로만 만든다.
            switch (Randomly.fromOptions(0, 1, 2, 3, 4, 5)) {
            case 0: {
                CUBRIDCompositeDataType operandType = CUBRIDCompositeDataType.getRandomStorable();
                return new CUBRIDBinaryOperation(generateExpression(operandType, depth + 1),
                        generateExpression(operandType, depth + 1), CUBRIDBinaryComparisonOperator.getRandom());
            }
            case 1:
                return new CUBRIDBinaryOperation(generateExpression(type, depth + 1),
                        generateExpression(type, depth + 1), CUBRIDBinaryLogicalOperator.getRandom());
            case 2:
                return isNull(generateExpression(CUBRIDCompositeDataType.getRandomStorable(), depth + 1));
            case 3: {
                // BETWEEN — 범위 술어는 인덱스 스캔으로 접히는 대표적인 형태다.
                CUBRIDCompositeDataType t = CUBRIDCompositeDataType.getRandomStorable();
                return new CUBRIDBetweenOperation(generateExpression(t, depth + 1),
                        generateExpression(t, depth + 1), generateExpression(t, depth + 1), Randomly.getBoolean());
            }
            case 4: {
                // IN — OR 사슬로 풀릴 수도, 인덱스 조회로 접힐 수도 있다.
                CUBRIDCompositeDataType t = CUBRIDCompositeDataType.getRandomStorable();
                List<CUBRIDExpression> elements = new ArrayList<>();
                int n = Randomly.smallNumber() + 1;
                for (int i = 0; i < n; i++) {
                    elements.add(generateExpression(t, depth + 1));
                }
                return new CUBRIDInOperation(generateExpression(t, depth + 1), elements, Randomly.getBoolean());
            }
            default:
                // NOT — 3치 논리에서 부정은 UNKNOWN 을 그대로 통과시킨다. NoREC 이 특히 좋아하는 지점.
                return negatePredicate(generateExpression(type, depth + 1));
            }
        }

        // 같은 타입끼리의 산술. 문자열/시간 타입에는 산술을 적용하지 않는다.
        if (isNumeric(type.getType()) && Randomly.getBoolean()) {
            return new CUBRIDBinaryOperation(generateExpression(type, depth + 1), generateExpression(type, depth + 1),
                    CUBRIDBinaryArithmeticOperator.getRandom());
        }
        return generateLeafNode(type);
    }

    private static boolean isNumeric(CUBRIDDataType t) {
        switch (t) {
        case INTEGER:
        case SMALLINT:
        case BIGINT:
        case DOUBLE:
            return true;
        default:
            return false;
        }
    }

    @Override
    public CUBRIDExpression generateConstant(CUBRIDCompositeDataType type) {
        if (Randomly.getBooleanWithSmallProbability()) {
            return CUBRIDConstant.createNullConstant();
        }
        Randomly r = globalState.getRandomly();
        switch (type.getType()) {
        case NULL:
            return CUBRIDConstant.createNullConstant();
        case BOOLEAN:
            // CUBRID 에 TRUE/FALSE 리터럴을 신뢰할 수 없으므로 항등 비교로 만든다.
            return CUBRIDConstant.createBooleanConstant(Randomly.getBoolean());
        case SMALLINT:
            return CUBRIDConstant.createIntConstant(r.getInteger(Short.MIN_VALUE, Short.MAX_VALUE));
        case INTEGER:
            return CUBRIDConstant.createIntConstant(r.getInteger(Integer.MIN_VALUE, Integer.MAX_VALUE));
        case BIGINT:
            return CUBRIDConstant.createIntConstant(r.getInteger());
        case DOUBLE:
            return CUBRIDConstant.createDoubleConstant(r.getDouble());
        case CHAR:
        case VARCHAR:
            return CUBRIDConstant.createStringConstant(r.getString(), Math.max(type.getSize(), 1));
        case DATE:
            return CUBRIDConstant.createDateConstant(r.getLong(0, System.currentTimeMillis()));
        case TIME:
            return CUBRIDConstant.createTimeConstant(r.getLong(0, System.currentTimeMillis()));
        case DATETIME:
            return CUBRIDConstant.createDatetimeConstant(r.getLong(0, System.currentTimeMillis()));
        default:
            throw new AssertionError("Unknown type: " + type.getType());
        }
    }

    @Override
    protected CUBRIDExpression generateColumn(CUBRIDCompositeDataType type) {
        List<CUBRIDColumn> candidates = columnsOfType(type);
        return new CUBRIDColumnReference(Randomly.fromList(candidates));
    }

    /** 크기는 무시하고 타입만 맞춘다 — VARCHAR(8) 컬럼도 VARCHAR 식에 쓸 수 있어야 한다. */
    private List<CUBRIDColumn> columnsOfType(CUBRIDCompositeDataType type) {
        return columns.stream().filter(c -> c.getType().getType() == type.getType()).collect(Collectors.toList());
    }

    @Override
    protected CUBRIDCompositeDataType getRandomType() {
        return CUBRIDCompositeDataType.getRandomStorable();
    }

    @Override
    protected boolean canGenerateColumnOfType(CUBRIDCompositeDataType type) {
        return !columnsOfType(type).isEmpty();
    }

    // ------------------------------------------------------------- 연산자

    public enum CUBRIDBinaryLogicalOperator implements BinaryOperatorNode.Operator {
        AND, OR;

        @Override
        public String getTextRepresentation() {
            return toString();
        }

        public static BinaryOperatorNode.Operator getRandom() {
            return Randomly.fromOptions(values());
        }
    }

    public enum CUBRIDBinaryComparisonOperator implements BinaryOperatorNode.Operator {
        EQUALS("="), GREATER(">"), GREATER_EQUALS(">="), SMALLER("<"), SMALLER_EQUALS("<="), NOT_EQUALS("<>");

        private final String textRepr;

        CUBRIDBinaryComparisonOperator(String textRepr) {
            this.textRepr = textRepr;
        }

        public static BinaryOperatorNode.Operator getRandom() {
            return Randomly.fromOptions(values());
        }

        @Override
        public String getTextRepresentation() {
            return textRepr;
        }
    }

    /** CUBRID 의 산술 연산자. {@code ||} 는 문자열 연결이므로 수치 산술에서 제외한다. */
    public enum CUBRIDBinaryArithmeticOperator implements BinaryOperatorNode.Operator {
        ADD("+"), SUB("-"), MULT("*"), DIV("/"), MOD("MOD");

        private final String textRepr;

        CUBRIDBinaryArithmeticOperator(String textRepr) {
            this.textRepr = textRepr;
        }

        public static BinaryOperatorNode.Operator getRandom() {
            return Randomly.fromOptions(values());
        }

        @Override
        public String getTextRepresentation() {
            return textRepr;
        }
    }

    // ------------------------------------------------- NoREC / TLP 공통부

    @Override
    public CUBRIDExpressionGenerator setTablesAndColumns(AbstractTables<CUBRIDTable, CUBRIDColumn> tables) {
        this.columns = tables.getColumns();
        this.tables = tables.getTables();
        return this;
    }

    @Override
    public CUBRIDExpression generateBooleanExpression() {
        return generatePredicate();
    }

    @Override
    public CUBRIDSelect generateSelect() {
        return new CUBRIDSelect();
    }

    @Override
    public List<CUBRIDJoin> getRandomJoinClauses() {
        List<CUBRIDJoin> joinExpressions = new ArrayList<>();
        while (tables.size() >= 2 && Randomly.getBooleanWithRatherLowProbability()) {
            CUBRIDTable leftTable = tables.remove(0);
            CUBRIDTable rightTable = tables.remove(0);
            List<CUBRIDColumn> joinColumns = new ArrayList<>(leftTable.getColumns());
            joinColumns.addAll(rightTable.getColumns());
            CUBRIDExpressionGenerator joinGen = new CUBRIDExpressionGenerator(globalState).setColumns(joinColumns);
            CUBRIDTableReference left = new CUBRIDTableReference(leftTable);
            CUBRIDTableReference right = new CUBRIDTableReference(rightTable);
            CUBRIDExpression onCondition = joinGen
                    .generateExpression(CUBRIDCompositeDataType.getRandomWithType(CUBRIDDataType.BOOLEAN));
            switch (CUBRIDJoin.JoinType.getRandom()) {
            case INNER:
                joinExpressions.add(CUBRIDJoin.createInnerJoin(left, right, onCondition));
                break;
            case LEFT:
                joinExpressions.add(CUBRIDJoin.createLeftOuterJoin(left, right, onCondition));
                break;
            case RIGHT:
                joinExpressions.add(CUBRIDJoin.createRightOuterJoin(left, right, onCondition));
                break;
            default:
                // CUBRID 는 NATURAL JOIN 을 지원하지만 컬럼명 우연 일치에 의존해 노이즈가 크다. 1차에서는 제외.
                joinExpressions.add(CUBRIDJoin.createInnerJoin(left, right, onCondition));
                break;
            }
        }
        return joinExpressions;
    }

    @Override
    public List<CUBRIDExpression> getTableRefs() {
        return tables.stream().map(CUBRIDTableReference::new).collect(Collectors.toList());
    }

    @Override
    public List<CUBRIDExpression> generateOrderBys() {
        List<CUBRIDExpression> expressions = new ArrayList<>();
        List<CUBRIDColumn> remaining = new ArrayList<>(columns);
        int nr = Randomly.smallNumber() + 1;
        for (int i = 0; i < nr && !remaining.isEmpty(); i++) {
            CUBRIDColumn c = Randomly.fromList(remaining);
            remaining.remove(c);
            expressions.add(new CUBRIDColumnReference(c));
        }
        return expressions;
    }

    @Override
    public List<CUBRIDExpression> generateFetchColumns(boolean shouldCreateDummy) {
        if (shouldCreateDummy) {
            return List.of(new CUBRIDColumnReference(new CUBRIDColumn("*", null, null)));
        }
        return Randomly.nonEmptySubset(
                columns.stream().map(c -> (CUBRIDExpression) new CUBRIDColumnReference(c)).collect(Collectors.toList()));
    }

    // ------------------------------------------------------------- NoREC

    /**
     * 최적화되는 쪽. {@code WHERE <pred>} 를 그대로 두어 옵티마이저가 술어를 자유롭게 재작성하게 한다.
     */
    @Override
    public String generateOptimizedQueryString(CUBRIDSelect select, CUBRIDExpression whereCondition,
            boolean shouldUseAggregate) {
        if (shouldUseAggregate) {
            select.setFetchColumns(List.of(new CUBRIDColumnReference(new CUBRIDColumn("COUNT(*)", null, null))));
        } else {
            select.setFetchColumns(
                    columns.stream().map(c -> (CUBRIDExpression) new CUBRIDColumnReference(c))
                            .collect(Collectors.toList()));
            if (Randomly.getBooleanWithSmallProbability()) {
                select.setOrderByClauses(generateOrderBys());
            }
        }
        select.setWhereClause(whereCondition);
        return select.asString();
    }

    /**
     * 최적화되지 않는 쪽 (NoREC 의 reference engine).
     *
     * <pre>
     * SELECT SUM(norec_count) FROM (
     *     SELECT CASE WHEN &lt;pred&gt; THEN 1 ELSE 0 END AS norec_count FROM &lt;from&gt; &lt;joins&gt;
     * ) AS res
     * </pre>
     *
     * 두 가지가 중요하다.
     * <ul>
     * <li><b>WHERE 를 반드시 지운다.</b> 술어가 WHERE 에 남아 있으면 두 질의가 같은 것을 세게 되어 oracle 이 아무것도 검출하지 못한다.
     * 상류의 DuckDB provider 는 WHERE 를 지우지 않고, HSQLDB provider 는 술어 자체를 {@code COUNT(*)} 로 대체해 술어를 잃는다 —
     * 둘 다 따르지 않았다.</li>
     * <li><b>{@code CASE WHEN} 을 쓴다.</b> CUBRID 에는 저장 가능한 BOOLEAN 이 없어 {@code CAST(pred AS INT)} 를
     * 신뢰할 수 없다. {@code CASE WHEN} 은 UNKNOWN 을 ELSE 로 보내므로 NoREC 이 요구하는 {@code IS TRUE} 의미와 정확히 같다.</li>
     * <li><b>별칭을 {@code count} 로 쓰면 안 된다.</b> CUBRID 에서 {@code count} 는 예약어(집계 함수)라
     * {@code SUM(count)} 가 함수 호출로 파싱되어 {@code Syntax error: unexpected ')', expecting '('} 가 난다.
     * 상류 DuckDB/HSQLDB provider 는 {@code count} 를 쓰지만 CUBRID 에서는 불가능하다.</li>
     * </ul>
     */
    @Override
    public String generateUnoptimizedQueryString(CUBRIDSelect select, CUBRIDExpression whereCondition) {
        String predicate = CUBRIDToStringVisitor.asString(whereCondition);
        select.setFetchColumns(List.of(new CUBRIDColumnReference(
                new CUBRIDColumn("CASE WHEN " + predicate + " THEN 1 ELSE 0 END AS norec_count", null, null))));
        select.setWhereClause(null);
        select.setOrderByClauses(List.of());
        return "SELECT SUM(norec_count) FROM (" + select.asString() + ") AS res";
    }
}
