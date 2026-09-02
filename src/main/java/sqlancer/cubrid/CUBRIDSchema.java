package sqlancer.cubrid;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
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
    private static final String COLUMN_QUERY = "SELECT attr_name, data_type, prec FROM db_attribute "
            + "WHERE class_name = '%s' ORDER BY def_order";

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
        CUBRIDTable t = new CUBRIDTable(name, columns, isView);
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

    private static List<CUBRIDColumn> getTableColumns(SQLConnection con, String tableName) throws SQLException {
        List<CUBRIDColumn> columns = new ArrayList<>();
        try (Statement s = con.createStatement();
                ResultSet rs = s.executeQuery(String.format(COLUMN_QUERY, tableName))) {
            while (rs.next()) {
                CUBRIDDataType type = CUBRIDDataType.from(rs.getString("data_type"));
                columns.add(new CUBRIDColumn(rs.getString("attr_name"), null,
                        new CUBRIDCompositeDataType(type, rs.getInt("prec"))));
            }
        }
        return columns;
    }

    public static class CUBRIDTable
            extends AbstractRelationalTable<CUBRIDSchema.CUBRIDColumn, TableIndex, CUBRIDProvider.CUBRIDGlobalState> {

        public CUBRIDTable(String tableName, List<CUBRIDSchema.CUBRIDColumn> columns, boolean isView) {
            super(tableName, columns, Collections.emptyList(), isView);
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

        INTEGER, SMALLINT, BIGINT, DOUBLE, CHAR, VARCHAR, DATE, TIME, DATETIME,
        /** 식 전용 — 컬럼으로 만들지 않는다. */
        BOOLEAN,
        /** 상수 NULL 전용. */
        NULL;

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
            if ("FLOAT".equals(t) || "NUMERIC".equals(t) || "MONETARY".equals(t)) {
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
        private final int size;
        private final CUBRIDDataType type;

        public CUBRIDCompositeDataType(CUBRIDDataType type, int size) {
            this.type = type;
            this.size = size;
        }

        /** 컬럼 정의에 쓸 수 있는 임의 타입. */
        public static CUBRIDCompositeDataType getRandomStorable() {
            return getRandomWithType(CUBRIDDataType.getRandomStorable());
        }

        public static CUBRIDCompositeDataType getRandomWithType(CUBRIDDataType type) {
            int size;
            switch (type) {
            case CHAR:
            case VARCHAR:
                size = Randomly.fromOptions(8, 16, 32);
                break;
            default:
                size = 0;
                break;
            }
            return new CUBRIDCompositeDataType(type, size);
        }

        public CUBRIDDataType getType() {
            return type;
        }

        public int getSize() {
            return size;
        }

        /** DDL 에 쓰는 표기. CHAR/VARCHAR 만 길이를 갖는다. */
        public String toSqlString() {
            if (size > 0) {
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
            return type == other.type && size == other.size;
        }

        @Override
        public int hashCode() {
            return type.hashCode() * 31 + size;
        }
    }
}
