package com.grandis.nova.common.web;

import com.grandis.nova.common.ErrorCode;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.Map;

/**
 * 모든 HTTP 응답의 봉투. 성공이든 실패든 같은 모양이다.
 *
 * <pre>
 * { "success": true,  "data": { ... }, "error": null, "timestamp": "...", "traceId": "..." }
 * { "success": false, "data": null, "error": { "code": "...", "message": "...", "details": null }, "timestamp": "...", "traceId": "..." }
 * </pre>
 *
 * 컨트롤러가 {@link #ok(Object)} 로 직접 감싼다. ResponseBodyAdvice 로 자동으로 감싸지 않는 이유 —
 * String 응답·actuator·이미 감싼 응답까지 걸려서 예외 규칙이 늘어난다. 감싸는 곳이 눈에 보이는 쪽이 낫다.
 *
 * 값이 없는 필드는 생략하지 않고 null 로 내보낸다. 클라이언트가 "없는 것" 과 "빈 것" 을 구분해야 한다.
 *
 * traceId 는 요청 ID 필터가 MDC 에 넣은 값이다. 필터가 없으면 null 이다.
 */
public record ApiResponse<T>(
        boolean success,
        T data,
        ApiError error,
        Instant timestamp,
        String traceId
) {

    /** 요청 ID 필터가 MDC 에 넣는 키. 로그와 응답이 같은 값을 쓴다. */
    public static final String TRACE_ID_KEY = "traceId";

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, Instant.now(), MDC.get(TRACE_ID_KEY));
    }

    public static ApiResponse<Void> fail(ErrorCode errorCode, String message) {
        return fail(errorCode, message, null);
    }

    public static ApiResponse<Void> fail(ErrorCode errorCode, String message, Map<String, Object> details) {
        return new ApiResponse<>(false, null, new ApiError(errorCode.name(), message, details),
                Instant.now(), MDC.get(TRACE_ID_KEY));
    }
}
