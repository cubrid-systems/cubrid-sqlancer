package sqlancer.cubrid.ast;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;

/** CUBRID 리터럴 표기. */
public class CUBRIDConstant implements CUBRIDExpression {

    private CUBRIDConstant() {
    }

    public static class CUBRIDNullConstant extends CUBRIDConstant {
        @Override
        public String toString() {
            return "NULL";
        }
    }

    public static class CUBRIDIntConstant extends CUBRIDConstant {
        private final long value;

        public CUBRIDIntConstant(long value) {
            this.value = value;
        }

        public long getValue() {
            return value;
        }

        @Override
        public String toString() {
            return String.valueOf(value);
        }
    }

    public static class CUBRIDDoubleConstant extends CUBRIDConstant {
        private final double value;

        public CUBRIDDoubleConstant(double value) {
            this.value = value;
        }

        public double getValue() {
            return value;
        }

        @Override
        public String toString() {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                // CUBRID 에는 NaN/Infinity 리터럴이 없다.
                return "NULL";
            }
            return String.valueOf(value);
        }
    }

    public static class CUBRIDTextConstant extends CUBRIDConstant {
        private final String value;

        public CUBRIDTextConstant(String value, int size) {
            String v = value == null ? "" : value;
            this.value = v.substring(0, Math.min(v.length(), size));
        }

        public String getValue() {
            return value;
        }

        @Override
        public String toString() {
            return "'" + value.replace("'", "''") + "'";
        }
    }

    /**
     * CUBRID 에는 저장 가능한 BOOLEAN 타입도, 이식성 있는 TRUE/FALSE 리터럴도 없다. 항등 비교로 대신한다.
     */
    public static class CUBRIDBooleanConstant extends CUBRIDConstant {
        private final boolean value;

        public CUBRIDBooleanConstant(boolean value) {
            this.value = value;
        }

        public boolean getValue() {
            return value;
        }

        @Override
        public String toString() {
            return value ? "(1 = 1)" : "(1 = 0)";
        }
    }

    /**
     * BOOLEAN 자리의 NULL. 맨 {@code NULL} 은 CUBRID 에서 술어가 될 수 없어 — {@code (NULL) OR (1 = 1)} 은 구문 오류다 —
     * UNKNOWN 을 주는 항등 비교로 쓴다. TRUE/FALSE 와 같은 방식이다.
     */
    public static class CUBRIDBooleanUnknownConstant extends CUBRIDConstant {
        @Override
        public String toString() {
            return "(1 = NULL)";
        }
    }

    private abstract static class CUBRIDTemporalConstant extends CUBRIDConstant {
        protected final String textRepr;

        CUBRIDTemporalConstant(long millis, String pattern) {
            this.textRepr = new SimpleDateFormat(pattern).format(new Timestamp(millis));
        }

        public String getValue() {
            return textRepr;
        }
    }

    public static class CUBRIDDateConstant extends CUBRIDTemporalConstant {
        public CUBRIDDateConstant(long millis) {
            super(millis, "yyyy-MM-dd");
        }

        @Override
        public String toString() {
            return String.format("DATE'%s'", textRepr);
        }
    }

    public static class CUBRIDTimeConstant extends CUBRIDTemporalConstant {
        public CUBRIDTimeConstant(long millis) {
            super(millis, "HH:mm:ss");
        }

        @Override
        public String toString() {
            return String.format("TIME'%s'", textRepr);
        }
    }

    public static class CUBRIDDatetimeConstant extends CUBRIDTemporalConstant {
        public CUBRIDDatetimeConstant(long millis) {
            super(millis, "yyyy-MM-dd HH:mm:ss");
        }

        @Override
        public String toString() {
            return String.format("DATETIME'%s'", textRepr);
        }
    }

    public static CUBRIDExpression createNullConstant() {
        return new CUBRIDNullConstant();
    }

    public static CUBRIDExpression createIntConstant(long val) {
        return new CUBRIDIntConstant(val);
    }

    public static CUBRIDExpression createDoubleConstant(double val) {
        return new CUBRIDDoubleConstant(val);
    }

    public static CUBRIDExpression createStringConstant(String text, int size) {
        return new CUBRIDTextConstant(text, size);
    }

    public static CUBRIDExpression createBooleanConstant(boolean val) {
        return new CUBRIDBooleanConstant(val);
    }

    public static CUBRIDExpression createBooleanUnknownConstant() {
        return new CUBRIDBooleanUnknownConstant();
    }

    public static CUBRIDExpression createDateConstant(long millis) {
        return new CUBRIDDateConstant(millis);
    }

    public static CUBRIDExpression createTimeConstant(long millis) {
        return new CUBRIDTimeConstant(millis);
    }

    public static CUBRIDExpression createDatetimeConstant(long millis) {
        return new CUBRIDDatetimeConstant(millis);
    }
}
