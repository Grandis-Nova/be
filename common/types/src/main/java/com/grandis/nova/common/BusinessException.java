package com.grandis.nova.common;

/**
 * 업무 규칙 위반. 예상된 실패이므로 스택트레이스를 채우지 않는다.
 *
 * 예상하지 못한 오류(NPE, DB 장애 등)는 이걸로 감싸지 않는다 —
 * 감싸면 500 이어야 할 것이 400 으로 나가고 원인이 로그에서 사라진다.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.defaultMessage(), null, false, false);
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message, null, false, false);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
