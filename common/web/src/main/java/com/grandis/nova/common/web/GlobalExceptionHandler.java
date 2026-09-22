package com.grandis.nova.common.web;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Map;

/**
 * HTTP 서비스 넷이 공유한다. 모든 오류를 {@link ApiResponse} 봉투로 내보낸다.
 *
 * 스프링 MVC 의 표준 예외(검증 실패 · 헤더/파라미터 누락 · 타입 불일치 · 깨진 본문 · 404 · 405 · 415 …)는
 * {@link ResponseEntityExceptionHandler} 가 알맞은 상태 코드와 헤더(405 의 Allow 등)로 처리한다.
 * 여기서는 그 응답의 본문만 봉투로 바꾸고({@link #handleExceptionInternal}), 필드별 사유가 필요한 경우만 따로 고친다.
 *
 * 내부 오류 원문을 응답에 담지 않는다(FR-U-04). 원인은 로그로만 남기고 사용자에게는 ErrorCode 의 문구를 보낸다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    // ── 업무 예외 · 서비스 계층 검증 · 그 밖의 예외 ─────────────────────────

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        ErrorCode code = e.errorCode();
        // 예상된 실패다. 5xx 로 정의된 업무 코드(예: 의존 서비스 무응답)만 경고로 남긴다.
        if (code.status() >= 500) {
            log.warn("업무 오류 {}: {}", code.name(), e.getMessage());
        }
        return ResponseEntity.status(code.status()).body(ApiResponse.fail(code, e.getMessage(), e.details()));
    }

    /** 서비스 계층의 @Validated 검증 위반. MVC 밖에서 나므로 부모가 모른다. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException e) {
        List<ApiError.Violation> violations = e.getConstraintViolations().stream()
                .map(v -> new ApiError.Violation(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return validationFailed(new HttpHeaders(), violations);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        // 예상하지 못한 오류만 여기 온다. 원문은 로그에만 남긴다.
        log.error("처리되지 않은 예외", e);
        ErrorCode code = CommonErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(code.status()).body(ApiResponse.fail(code, code.defaultMessage()));
    }

    // ── 스프링 MVC 표준 예외: 필드별 사유가 필요한 것만 고친다 ──────────────

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ApiError.Violation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiError.Violation(f.getField(), f.getDefaultMessage()))
                .toList();
        return validationFailed(headers, violations);
    }

    /** 경로·쿼리·헤더 파라미터의 제약 위반(@Min · @Size 등을 컨트롤러 파라미터에 단 경우). */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ApiError.Violation> violations = ex.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream()
                        .map(err -> new ApiError.Violation(
                                r.getMethodParameter().getParameterName(), err.getDefaultMessage())))
                .toList();
        return validationFailed(headers, violations);
    }

    /** 필수 헤더 누락. Idempotency-Key 는 계약상 전용 코드가 있다. */
    @Override
    protected ResponseEntity<Object> handleServletRequestBindingException(
            ServletRequestBindingException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (ex instanceof MissingRequestHeaderException missing) {
            if (IDEMPOTENCY_KEY_HEADER.equalsIgnoreCase(missing.getHeaderName())) {
                return envelope(headers, CommonErrorCode.IDEMPOTENCY_KEY_REQUIRED, null);
            }
            return validationFailed(headers,
                    List.of(new ApiError.Violation(missing.getHeaderName(), "필수 헤더입니다.")));
        }
        return super.handleServletRequestBindingException(ex, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        return validationFailed(headers, List.of(new ApiError.Violation(ex.getParameterName(), "필수 항목입니다.")));
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String name = ex instanceof MethodArgumentTypeMismatchException m ? m.getName() : ex.getPropertyName();
        return validationFailed(headers, List.of(new ApiError.Violation(name, "형식이 올바르지 않습니다.")));
    }

    /**
     * 부모가 만든 모든 응답이 여기를 지난다. 상태 코드와 헤더는 그대로 두고 본문만 봉투로 바꾼다.
     * 부모의 기본 본문(ProblemDetail)에는 예외 메시지가 섞일 수 있어 쓰지 않는다.
     * 이미 봉투면(위에서 고친 경우) 그대로 둔다.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (body instanceof ApiResponse<?>) {
            return super.handleExceptionInternal(ex, body, headers, status, request);
        }
        ErrorCode code = codeOf(status);
        if (status.is5xxServerError()) {
            log.error("MVC 처리 중 서버 오류", ex);
        }
        return ResponseEntity.status(status).headers(headers)
                .body(ApiResponse.fail(code, code.defaultMessage()));
    }

    /**
     * 상태 코드별 대표 코드. 계약에 코드가 있는 상태(401 · 403 · 404 · 405 · 503)는 그 코드로,
     * 계약에 없는 4xx(415 · 406 등)는 "요청이 잘못됐다" 로 묶되 상태 코드는 그대로 둔다.
     */
    private static ErrorCode codeOf(HttpStatusCode status) {
        return switch (status.value()) {
            case 401 -> CommonErrorCode.UNAUTHENTICATED;
            case 403 -> CommonErrorCode.FORBIDDEN;
            case 404 -> CommonErrorCode.NOT_FOUND;
            case 405 -> CommonErrorCode.METHOD_NOT_ALLOWED;
            case 503 -> CommonErrorCode.DEPENDENCY_UNAVAILABLE;
            default -> status.is4xxClientError() ? CommonErrorCode.VALIDATION_FAILED : CommonErrorCode.INTERNAL_ERROR;
        };
    }

    private static ResponseEntity<Object> validationFailed(HttpHeaders headers, List<ApiError.Violation> violations) {
        return envelope(headers, CommonErrorCode.VALIDATION_FAILED, Map.of("violations", violations));
    }

    private static ResponseEntity<Object> envelope(HttpHeaders headers, ErrorCode code, Map<String, Object> details) {
        return ResponseEntity.status(code.status()).headers(headers)
                .body(ApiResponse.fail(code, code.defaultMessage(), details));
    }
}
