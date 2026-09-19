package com.grandis.nova.common;

/**
 * 어느 모듈에서나 쓰는 코드만 둔다. 이름과 문구는 api-spec 공통 오류 그대로다 — 프론트가 code 문자열로 분기하므로 바꾸지 않는다.
 *
 * 도메인 코드(재고 부족 · 신청 마감 등)는 여기가 아니라 해당 모듈에 만든다.
 * 판단 기준: "이 코드를 두 개 이상의 모듈이 던지는가". 아니면 여기 오면 안 된다.
 */
public enum CommonErrorCode implements ErrorCode {
    VALIDATION_FAILED(400, "입력값을 확인해 주세요."),
    UNAUTHENTICATED(401, "로그인이 필요합니다."),
    FORBIDDEN(403, "권한이 없습니다."),
    RESOURCE_NOT_FOUND(404, "대상을 찾을 수 없습니다."),
    /** api-spec 공통 오류 목록엔 없다. 스프링 MVC 가 내는 405/415 를 봉투로 감쌀 때만 쓴다. */
    METHOD_NOT_ALLOWED(405, "허용되지 않는 요청 방식입니다."),
    STATE_CONFLICT(409, "현재 상태와 충돌합니다. 다시 조회해 주세요."),
    UNSUPPORTED_MEDIA_TYPE(415, "지원하지 않는 요청 형식입니다."),
    INTERNAL_ERROR(500, "일시적인 오류가 발생했습니다.");

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
