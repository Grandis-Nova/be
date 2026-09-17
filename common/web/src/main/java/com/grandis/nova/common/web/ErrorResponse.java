package com.grandis.nova.common.web;

import com.grandis.nova.common.ErrorCode;

import java.time.Instant;
import java.util.List;

/**
 * 오류 응답 본문.
 *
 * fieldErrors 는 검증 실패에서만 채운다. 그 외에는 null 이고 필드를 생략하지 않는다 —
 * 클라이언트가 "없는 것" 과 "빈 것" 을 구분해야 한다.
 */
public record ErrorResponse(
        String code,
        String message,
        List<FieldError> fieldErrors,
        Instant timestamp
) {

    public record FieldError(String field, String reason) {
    }

    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return new ErrorResponse(errorCode.name(), message, null, Instant.now());
    }

    public static ErrorResponse of(ErrorCode errorCode, String message, List<FieldError> fieldErrors) {
        return new ErrorResponse(errorCode.name(), message, fieldErrors, Instant.now());
    }
}
