package sqlancer.cubrid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import sqlancer.cubrid.CUBRIDSchema.CUBRIDCompositeDataType;
import sqlancer.cubrid.CUBRIDSchema.CUBRIDDataType;

/**
 * 타입 모델. DDL 표기와 카탈로그 매핑은 엔진이 받는 형태에 맞춰야 하고, 그 형태는 엔진에 물어 확인한 것이다 —
 * 정밀도 39 는 거부되고, {@code FLOAT(n)} 은 DOUBLE 로 승격되며, {@code DECIMAL} 은 카탈로그에 NUMERIC 으로 보고된다.
 */
class CUBRIDTypeModelTest {

    // ------------------------------------------------------------ DDL 표기

    @Test
    void numericCarriesPrecisionAndScale() {
        assertEquals("NUMERIC(10, 3)",
                new CUBRIDCompositeDataType(CUBRIDDataType.NUMERIC, 10, 3).toSqlString());
        assertEquals("NUMERIC(38, 38)",
                new CUBRIDCompositeDataType(CUBRIDDataType.NUMERIC, 38, 38).toSqlString());
    }

    @Test
    void charAndVarcharCarryALength() {
        assertEquals("CHAR(8)", new CUBRIDCompositeDataType(CUBRIDDataType.CHAR, 8).toSqlString());
        assertEquals("VARCHAR(32)", new CUBRIDCompositeDataType(CUBRIDDataType.VARCHAR, 32).toSqlString());
    }

    /**
     * FLOAT 에 정밀도를 붙이면 CUBRID 가 DOUBLE 로 승격한다 — 카탈로그가 그렇게 보고하므로 스키마를 다시 읽는 순간 타입이 어긋난다.
     */
    @Test
    void floatIsEmittedWithoutAPrecision() {
        assertEquals("FLOAT", new CUBRIDCompositeDataType(CUBRIDDataType.FLOAT, 7).toSqlString());
        assertEquals("DOUBLE", new CUBRIDCompositeDataType(CUBRIDDataType.DOUBLE, 15).toSqlString());
        assertEquals("INTEGER", new CUBRIDCompositeDataType(CUBRIDDataType.INTEGER, 0).toSqlString());
    }

    // ------------------------------------------------------------ 카탈로그 매핑

    @Test
    void catalogNamesMapOntoTheModel() {
        assertEquals(CUBRIDDataType.NUMERIC, CUBRIDDataType.from("NUMERIC"));
        assertEquals(CUBRIDDataType.NUMERIC, CUBRIDDataType.from("DECIMAL"));
        assertEquals(CUBRIDDataType.FLOAT, CUBRIDDataType.from("FLOAT"));
        assertEquals(CUBRIDDataType.VARCHAR, CUBRIDDataType.from("STRING"));
        assertEquals(CUBRIDDataType.DATETIME, CUBRIDDataType.from("TIMESTAMP"));
        // MONETARY 는 컬럼으로 만들지 않지만 읽어올 수는 있다.
        assertEquals(CUBRIDDataType.DOUBLE, CUBRIDDataType.from("MONETARY"));
    }

    @Test
    void floatIsNoLongerFoldedIntoDouble() {
        assertNotEquals(CUBRIDDataType.DOUBLE, CUBRIDDataType.from("FLOAT"));
        assertNotEquals(CUBRIDDataType.DOUBLE, CUBRIDDataType.from("NUMERIC"));
    }

    @Test
    void unknownNamesBecomeNull() {
        assertEquals(CUBRIDDataType.NULL, CUBRIDDataType.from(null));
        assertEquals(CUBRIDDataType.NULL, CUBRIDDataType.from("SET_OF(INTEGER)"));
    }

    // ------------------------------------------------------------ 숫자 판정

    /**
     * 집계 인자 선택과 연산자 선택이 이 판정을 공유한다. 숫자가 아닌 컬럼에 SUM 을 걸면
     * {@code Cannot coerce ... to type double} 이고, MIN/MAX 를 정수 상수와 비교하면
     * {@code Cannot coerce 0 to type date} 다.
     */
    @Test
    void numericTypesAreTheOnesComparableToAnIntegerConstant() {
        for (CUBRIDDataType type : new CUBRIDDataType[] { CUBRIDDataType.INTEGER, CUBRIDDataType.SMALLINT,
                CUBRIDDataType.BIGINT, CUBRIDDataType.DOUBLE, CUBRIDDataType.FLOAT, CUBRIDDataType.NUMERIC }) {
            assertTrue(type.isNumeric(), type + " 는 숫자여야 한다");
        }
        for (CUBRIDDataType type : new CUBRIDDataType[] { CUBRIDDataType.CHAR, CUBRIDDataType.VARCHAR,
                CUBRIDDataType.DATE, CUBRIDDataType.TIME, CUBRIDDataType.DATETIME, CUBRIDDataType.BOOLEAN,
                CUBRIDDataType.NULL }) {
            assertFalse(type.isNumeric(), type + " 는 숫자가 아니어야 한다");
        }
    }

    // ------------------------------------------------------------ 무작위 생성

    /** BOOLEAN 은 CUBRID 의 컬럼 타입이 아니고 NULL 은 상수 전용이다. 둘 중 하나라도 나오면 DDL 이 깨진다. */
    @Test
    void randomStorableNeverYieldsBooleanOrNull() {
        for (int i = 0; i < 2000; i++) {
            CUBRIDDataType type = CUBRIDDataType.getRandomStorable();
            assertNotEquals(CUBRIDDataType.BOOLEAN, type);
            assertNotEquals(CUBRIDDataType.NULL, type);
        }
    }

    /** 스케일이 정밀도를 넘거나 정밀도가 38 을 넘으면 CUBRID 가 거부한다. */
    @Test
    void randomNumericStaysWithinEngineBounds() {
        for (int i = 0; i < 2000; i++) {
            CUBRIDCompositeDataType type = CUBRIDCompositeDataType.getRandomWithType(CUBRIDDataType.NUMERIC);
            int precision = type.getSize();
            int scale = type.getScale();
            assertTrue(precision >= 1 && precision <= CUBRIDCompositeDataType.MAX_NUMERIC_PRECISION,
                    "정밀도가 범위를 벗어났다: " + precision);
            assertTrue(scale >= 0 && scale <= precision, "스케일이 정밀도를 넘었다: " + type.toSqlString());
        }
    }
}
