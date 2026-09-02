package sqlancer.cubrid;

import java.util.ArrayList;
import java.util.List;

import sqlancer.common.query.ExpectedErrors;

/**
 * 무작위 생성 SQL 이 자연스럽게 유발하는, 버그가 아닌 오류 목록.
 *
 * 문자열은 CUBRID 가 JDBC 로 돌려주는 메시지의 부분 일치로 검사된다. 실제 실행에서 새 오류가 나오면 여기에 추가하되, *논리 버그를 감출 수 있는*
 * 광범위한 패턴은 넣지 않는다.
 */
public final class CUBRIDErrors {

    private CUBRIDErrors() {
    }

    public static List<String> getExpressionErrors() {
        List<String> errors = new ArrayList<>();

        // 타입/변환
        errors.add("Cannot coerce");
        errors.add("cannot coerce");
        errors.add("Cast operation");
        errors.add("is not defined on");
        errors.add("Argument of");
        errors.add("Semantic:");

        // 수치
        errors.add("Division by zero");
        errors.add("division by zero");
        // CUBRID 의 실제 문구. "Division by zero" 만으로는 잡히지 않는다.
        errors.add("Attempt to divide by zero");
        errors.add("overflow");
        errors.add("Overflow");
        errors.add("out of range");
        errors.add("Data overflow");
        errors.add("data overflow");
        errors.add("Overflow occurred");

        // 문자열/날짜
        errors.add("Conversion error");
        errors.add("date/time conversion");
        errors.add("Invalid format");
        errors.add("data type is not");

        return errors;
    }

    public static void addExpressionErrors(ExpectedErrors errors) {
        errors.addAll(getExpressionErrors());
    }

    public static List<String> getInsertErrors() {
        List<String> errors = new ArrayList<>(getExpressionErrors());
        errors.add("exceeds the maximum length");
        errors.add("is not allowed to be NULL");
        errors.add("duplicate");
        errors.add("Duplicate");
        // UNIQUE 인덱스가 있는 테이블에 무작위 값을 넣으면 당연히 부딪힌다.
        errors.add("unique constraint violations");
        return errors;
    }

    public static void addInsertErrors(ExpectedErrors errors) {
        errors.addAll(getInsertErrors());
    }
}
