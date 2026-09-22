package com.grandis.nova.common;

/**
 * 어느 모듈에서나 쓰는 코드만 둔다.
 *
 * 도메인 코드(재고 부족 · 신청 마감 등)는 여기가 아니라 해당 모듈에 만든다.
 * 판단 기준: "이 코드를 두 개 이상의 모듈이 던지는가". 아니면 여기 오면 안 된다.
 *
 * 이름은 API 계약(contracts/openapi.yaml 의 ErrorCode)과 같아야 한다. 클라이언트는 이 이름으로 분기한다.
 */
public enum CommonErrorCode implements ErrorCode {

    VALIDATION_FAILED(400, "요청 값이 올바르지 않습니다."),
    IDEMPOTENCY_KEY_REQUIRED(400, "Idempotency-Key 헤더가 필요합니다."),
    UNAUTHENTICATED(401, "로그인이 필요합니다."),
    FORBIDDEN(403, "권한이 없습니다."),
    NOT_FOUND(404, "대상을 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(405, "지원하지 않는 요청 방식입니다."),
    INTERNAL_ERROR(500, "일시적인 오류가 발생했습니다."),
    DEPENDENCY_UNAVAILABLE(503, "잠시 후 다시 시도해 주세요.");

    private final int status;
    private final String message;

    CommonErrorCode(int status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String defaultMessage() {
        return message;
    }
}
