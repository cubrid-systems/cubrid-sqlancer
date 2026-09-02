package sqlancer.cubrid;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import sqlancer.Randomly;
import sqlancer.SQLConnection;
import sqlancer.common.schema.AbstractRelationalTable;
import sqlancer.common.schema.AbstractSchema;
import sqlancer.common.schema.AbstractTableColumn;
import sqlancer.common.schema.TableIndex;

public class CUBRIDSchema extends AbstractSchema<CUBRIDProvider.CUBRIDGlobalState, CUBRIDSchema.CUBRIDTable> {

    /** 사용자 테이블만 (시스템 클래스 제외). */
    private static final String TABLE_QUERY = "SELECT class_name FROM db_class "
            + "WHERE is_system_class = 'NO' AND class_type = 'CLASS'";

    private static final String VIEW_QUERY = "SELECT class_name FROM db_class "
            + "WHERE is_system_class = 'NO' AND class_type = 'VCLASS'";

    /** def_order 로 정렬해야 INSERT 의 컬럼 순서와 일치한다. */
    private static final String COLUMN_QUERY = "SELECT attr_name, data_type, prec, scale FROM db_attribute "
            + "WHERE class_name = '%s' ORDER BY def_order";

    /**
     * 인덱스를 읽어오지 않으면 {@code getFreeIndexName()} 이 항상 {@code i0} 을 돌려주고, 모든 CREATE INDEX 가
     * {@code Index "i0" already defined} 로 실패한다. 실제로 한 세션에서 46건이 그렇게 낭비됐다.
     */
    private static final String INDEX_QUERY = "SELECT index_name FROM db_index WHERE class_name = '%s'";

    public CUBRIDSchema(List<CUBRIDTable> databaseTables) {
        super(databaseTables);
    }

    public static CUBRIDSchema fromConnection(SQLConnection connection, String databaseName) throws SQLException {
        List<CUBRIDTable> databaseTables = new ArrayList<>();
        for (String tableName : getNames(connection, TABLE_QUERY)) {
            databaseTables.add(buildTable(connection, tableName, false));
        }
        for (String viewName : getNames(connection, VIEW_QUERY)) {
            databaseTables.add(buildTable(connection, viewName, true));
        }
        return new CUBRIDSchema(databaseTables);
    }

    private static CUBRIDTable buildTable(SQLConnection connection, String name, boolean isView) throws SQLException {
        List<CUBRIDColumn> columns = getTableColumns(connection, name);
        CUBRIDTable t = new CUBRIDTable(name, columns, getTableIndexes(connection, name), isView);
        for (CUBRIDColumn c : columns) {
            c.setTable(t);
        }
        return t;
    }

    private static List<String> getNames(SQLConnection con, String query) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement s = con.createStatement(); ResultSet rs = s.executeQuery(query)) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    private static List<TableIndex> getTableIndexes(SQLConnection con, String tableName) throws SQLException {
        List<TableIndex> indexes = new ArrayList<>();
        try (Statement s = con.createStatement();
                ResultSet rs = s.executeQuery(String.format(INDEX_QUERY, tableName))) {
            while (rs.next()) {
                indexes.add(TableIndex.create(rs.getString("index_name")));
            }
        }
        return indexes;
    }

    private static List<CUBRIDColumn> getTableColumns(SQLConnection con, String tableName) throws SQLException {
        List<CUBRIDColumn> columns = new ArrayList<>();
        try (Statement s = con.createStatement();
                ResultSet rs = s.executeQuery(String.format(COLUMN_QUERY, tableName))) {
            while (rs.next()) {
                CUBRIDDataType type = CUBRIDDataType.from(rs.getString("data_type"));
                columns.add(new CUBRIDColumn(rs.getString("attr_name"), null,
                        new CUBRIDCompositeDataType(type, rs.getInt("prec"), rs.getInt("scale"))));
            }
        }
        return columns;
    }

    public static class CUBRIDTable
            extends AbstractRelationalTable<CUBRIDSchema.CUBRIDColumn, TableIndex, CUBRIDProvider.CUBRIDGlobalState> {

        public CUBRIDTable(String tableName, List<CUBRIDSchema.CUBRIDColumn> columns, List<TableIndex> indexes,
                boolean isView) {
            super(tableName, columns, indexes, isView);
        }
    }

    public static class CUBRIDColumn
            extends AbstractTableColumn<CUBRIDSchema.CUBRIDTable, CUBRIDSchema.CUBRIDCompositeDataType> {

        public CUBRIDColumn(String name, CUBRIDTable table, CUBRIDCompositeDataType type) {
            super(name, table, type);
        }
    }

    /**
     * CUBRID 의 타입.
     *
     * ⚠️ BOOLEAN 은 <em>컬럼 타입이 아니다</em>. CUBRID 에는 저장 가능한 boolean 타입이 없고 비교/논리 연산의 결과로만 존재한다. 따라서 BOOLEAN
     * 은 식(expression) 생성에만 쓰이며 {@link #getRandomStorable()} 이 절대 돌려주지 않는다.
     */
    public enum CUBRIDDataType {

        INTEGER, SMALLINT, BIGINT, DOUBLE,
        /** 단정도. CUBRID 는 정밀도가 7 을 넘으면 DOUBLE 로 승격한다. */
        FLOAT,
        /** 고정 소수점. 정밀도 최대 38, 스케일은 정밀도 이하. */
        NUMERIC,
        CHAR, VARCHAR, DATE, TIME, DATETIME,
        /** 식 전용 — 컬럼으로 만들지 않는다. */
        BOOLEAN,
        /** 상수 NULL 전용. */
        NULL;

        /** 정수 상수와 비교할 수 있는 타입. 집계 인자와 연산자 선택이 이 판정을 공유한다. */
        public boolean isNumeric() {
            switch (this) {
            case INTEGER:
            case SMALLINT:
            case BIGINT:
            case DOUBLE:
            case FLOAT:
            case NUMERIC:
                return true;
            default:
                return false;
            }
        }

        /** 컬럼으로 만들 수 있는 타입만. */
        public static CUBRIDDataType getRandomStorable() {
            CUBRIDDataType dt;
            do {
                dt = Randomly.fromOptions(values());
            } while (dt == NULL || dt == BOOLEAN);
            return dt;
        }

        public static CUBRIDDataType from(String typeName) {
            if (typeName == null) {
                return NULL;
            }
            String t = typeName.trim().toUpperCase();
            // CUBRID 는 VARCHAR 를 STRING 으로 보고하기도 한다.
            if ("STRING".equals(t)) {
                return VARCHAR;
            }
            if ("TIMESTAMP".equals(t)) {
                return DATETIME;
            }
            if ("DECIMAL".equals(t)) {
                return NUMERIC;
            }
            // MONETARY 는 컬럼으로 만들지 않지만 읽어올 수는 있다.
            if ("MONETARY".equals(t)) {
                return DOUBLE;
            }
            for (CUBRIDDataType value : values()) {
                if (value.name().equals(t)) {
                    return value;
                }
            }
            return NULL;
        }
    }

    public static class CUBRIDCompositeDataType {

        /** CUBRID 의 NUMERIC 정밀도 상한. 39 는 "Precision (39) too large" 로 거부된다. */
        public static final int MAX_NUMERIC_PRECISION = 38;

        private final int size;
        private final int scale;
        private final CUBRIDDataType type;

        public CUBRIDCompositeDataType(CUBRIDDataType type, int size) {
            this(type, size, 0);
        }

        public CUBRIDCompositeDataType(CUBRIDDataType type, int size, int scale) {
            this.type = type;
            this.size = size;
            this.scale = scale;
        }

        /** 컬럼 정의에 쓸 수 있는 임의 타입. */
        public static CUBRIDCompositeDataType getRandomStorable() {
            return getRandomWithType(CUBRIDDataType.getRandomStorable());
        }

        public static CUBRIDCompositeDataType getRandomWithType(CUBRIDDataType type) {
            switch (type) {
            case CHAR:
            case VARCHAR:
                return new CUBRIDCompositeDataType(type, Randomly.fromOptions(8, 16, 32));
            case NUMERIC: {
                int precision = 1 + (int) Randomly.getNotCachedInteger(0, MAX_NUMERIC_PRECISION);
                int scale = (int) Randomly.getNotCachedInteger(0, precision + 1);
                return new CUBRIDCompositeDataType(type, precision, scale);
            }
            default:
                return new CUBRIDCompositeDataType(type, 0);
            }
        }

        public CUBRIDDataType getType() {
            return type;
        }

        public int getSize() {
            return size;
        }

        public int getScale() {
            return scale;
        }

        /**
         * DDL 에 쓰는 표기. CHAR/VARCHAR 는 길이, NUMERIC 은 정밀도와 스케일을 갖는다. 나머지에 괄호를 붙이면 CUBRID 가 거부하거나
         * (FLOAT 처럼) 다른 타입으로 승격한다.
         */
        public String toSqlString() {
            if (type == CUBRIDDataType.NUMERIC) {
                return "NUMERIC(" + size + ", " + scale + ")";
            }
            if (size > 0 && (type == CUBRIDDataType.CHAR || type == CUBRIDDataType.VARCHAR)) {
                return type.name() + "(" + size + ")";
            }
            return type.name();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof CUBRIDCompositeDataType)) {
                return false;
            }
            CUBRIDCompositeDataType other = (CUBRIDCompositeDataType) o;
            return type == other.type && size == other.size && scale == other.scale;
        }

        @Override
        public int hashCode() {
            return (type.hashCode() * 31 + size) * 31 + scale;
        }
    }
}
