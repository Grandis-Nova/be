package com.grandis.nova.common;

import java.util.Map;

/**
 * 업무 규칙 위반. 예상된 실패이므로 스택트레이스를 채우지 않는다.
 *
 * 예상하지 못한 오류(NPE, DB 장애 등)는 이걸로 감싸지 않는다 —
 * 감싸면 500 이어야 할 것이 400 으로 나가고 원인이 로그에서 사라진다.
 *
 * details 는 응답의 error.details 로 그대로 나간다(예: existingPreorderId, fields).
 * 사용자에게 보여도 되는 값만 담는다. 내부 식별자·원문 오류를 넣지 않는다.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, Object> details;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), null);
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BusinessException(ErrorCode errorCode, Map<String, Object> details) {
        this(errorCode, errorCode.defaultMessage(), details);
    }

    public BusinessException(ErrorCode errorCode, String message, Map<String, Object> details) {
        super(message, null, false, false);
        this.errorCode = errorCode;
        this.details = details == null ? null : Map.copyOf(details);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** 없으면 null. 응답에서 "없음" 과 "빈 것" 을 구분하기 위해 빈 Map 으로 바꾸지 않는다. */
    public Map<String, Object> details() {
        return details;
    }
}
