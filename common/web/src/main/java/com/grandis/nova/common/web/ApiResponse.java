package com.grandis.nova.common.web;

import com.grandis.nova.common.ErrorCode;
import java.time.Instant;
import org.slf4j.MDC;

/**
 * api-spec 공통 규칙의 응답 봉투. 성공은 {success=true, data, error=null}, 오류는 {success=false, data=null, error}.
 * 모든 HTTP 응답이 이 모양이어야 프론트가 한 가지로 읽는다. 401·403(보안 필터)과 400·404·500(전역 예외 처리)이 같은 클래스를 쓴다.
 *
 * traceId 는 MdcContextFilter 가 요청마다 MDC 에 넣은 값이다. 필터 밖(스케줄러 등)에서 만들면 null 이다.
 * error.detail 은 검증 실패의 필드 정보처럼 클라이언트가 처리할 수 있는 것만 담는다. 내부 오류 원문은 넣지 않는다(FR-U-04).
 */
public record ApiResponse<T>(
        boolean success,
        T data,
        ApiError error,
        Instant timestamp,
        String traceId
) {

    public record ApiError(String code, String message, Object detail, boolean retryable) {
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, Instant.now(), currentTraceId());
    }

    public static ApiResponse<Void> error(ErrorCode code, String message, Object detail, boolean retryable) {
        return new ApiResponse<>(false, null, new ApiError(code.name(), message, detail, retryable), Instant.now(), currentTraceId());
    }

    public static ApiResponse<Void> error(ErrorCode code) {
        return error(code, code.defaultMessage(), null, false);
    }

    private static String currentTraceId() {
        return MDC.get(MdcContextFilter.TRACE_ID);
    }
}
