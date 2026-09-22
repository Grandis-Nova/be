package com.grandis.nova.common.web;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    // ── 업무 예외 · 그 밖의 예외 ─────────────────────────────────────────

    @Test
    void 업무_예외는_코드의_상태와_봉투로_나가고_traceId_를_싣는다() {
        MDC.put(ApiResponse.TRACE_ID_KEY, "trace-1");

        ResponseEntity<ApiResponse<Void>> res =
                handler.handleBusiness(new BusinessException(CommonErrorCode.NOT_FOUND));

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        ApiResponse<Void> body = res.getBody();
        assertThat(body.success()).isFalse();
        assertThat(body.data()).isNull();
        assertThat(body.error().code()).isEqualTo("NOT_FOUND");
        assertThat(body.error().message()).isEqualTo(CommonErrorCode.NOT_FOUND.defaultMessage());
        assertThat(body.error().details()).isNull();
        assertThat(body.timestamp()).isNotNull();
        assertThat(body.traceId()).isEqualTo("trace-1");
    }

    @Test
    void 업무_예외의_details_는_error_details_로_나간다() {
        BusinessException e = new BusinessException(CommonErrorCode.VALIDATION_FAILED,
                Map.of("fields", List.of("optionId")));

        assertThat(handler.handleBusiness(e).getBody().error().details())
                .isEqualTo(Map.of("fields", List.of("optionId")));
    }

    @Test
    void 예상하지_못한_예외는_원문을_숨기고_500() {
        ResponseEntity<ApiResponse<Void>> res =
                handler.handleUnexpected(new IllegalStateException("SQL 원문이 여기 있다"));

        assertThat(res.getStatusCode().value()).isEqualTo(500);
        assertThat(res.getBody().error().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(res.getBody().error().message()).doesNotContain("SQL");
    }

    // ── 스프링 MVC 표준 예외 — 부모의 공개 진입점으로 흘려 본다 ──────────────

    @Test
    void 본문_검증_실패는_details_violations_로_필드별_사유를_담는다() throws Exception {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "request");
        binding.addError(new FieldError("request", "optionId", "필수 항목입니다."));

        ApiResponse<?> body = handle(new MethodArgumentNotValidException(parameter(), binding), 400);

        assertThat(body.error().code()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.error().details()).isEqualTo(
                Map.of("violations", List.of(new ApiError.Violation("optionId", "필수 항목입니다."))));
    }

    @Test
    void 멱등키_헤더가_없으면_IDEMPOTENCY_KEY_REQUIRED() throws Exception {
        ApiResponse<?> body = handle(new MissingRequestHeaderException("Idempotency-Key", parameter()), 400);

        assertThat(body.error().code()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    }

    @Test
    void 다른_헤더가_없으면_VALIDATION_FAILED_와_헤더_이름() throws Exception {
        ApiResponse<?> body = handle(new MissingRequestHeaderException("X-Admission-Ticket", parameter()), 400);

        assertThat(body.error().code()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.error().details()).isEqualTo(Map.of("violations",
                List.of(new ApiError.Violation("X-Admission-Ticket", "필수 헤더입니다."))));
    }

    @Test
    void 쿼리_파라미터가_없으면_400_과_이름() throws Exception {
        ApiResponse<?> body = handle(new MissingServletRequestParameterException("productId", "Long"), 400);

        assertThat(body.error().details()).isEqualTo(Map.of("violations",
                List.of(new ApiError.Violation("productId", "필수 항목입니다."))));
    }

    @Test
    void 타입이_틀리면_400_과_이름() throws Exception {
        MethodArgumentTypeMismatchException e = new MethodArgumentTypeMismatchException(
                "abc", Long.class, "productId", parameter(), new NumberFormatException());

        ApiResponse<?> body = handle(e, 400);

        assertThat(body.error().details()).isEqualTo(Map.of("violations",
                List.of(new ApiError.Violation("productId", "형식이 올바르지 않습니다."))));
    }

    @Test
    void 깨진_JSON_은_파서_메시지를_숨기고_400() throws Exception {
        HttpMessageNotReadableException e = new HttpMessageNotReadableException(
                "JSON parse error: Unexpected character at [Source: com.example.Internal]",
                new MockHttpInputMessage(new byte[0]));

        ApiResponse<?> body = handle(e, 400);

        assertThat(body.error().code()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.error().message()).doesNotContain("Source", "com.example");
    }

    @Test
    void 없는_경로는_404() throws Exception {
        ApiResponse<?> body = handle(new NoResourceFoundException(HttpMethod.GET, "/nope", "nope"), 404);

        assertThat(body.error().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void 지원하지_않는_메서드는_405_와_Allow_헤더() throws Exception {
        ResponseEntity<Object> res = handler.handleException(
                new HttpRequestMethodNotSupportedException("DELETE", Set.of("GET", "POST")), request());

        assertThat(res.getStatusCode().value()).isEqualTo(405);
        assertThat(res.getHeaders().getAllow()).containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.POST);
        assertThat(((ApiResponse<?>) res.getBody()).error().code()).isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    void 계약에_없는_4xx_는_상태를_유지하고_VALIDATION_FAILED() throws Exception {
        ApiResponse<?> body = handle(
                new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON)), 415);

        assertThat(body.error().code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void 성공_봉투는_error_가_null() {
        ApiResponse<String> res = ApiResponse.ok("hello");

        assertThat(res.success()).isTrue();
        assertThat(res.data()).isEqualTo("hello");
        assertThat(res.error()).isNull();
        assertThat(res.traceId()).isNull();  // 요청 ID 필터를 거치지 않으면 null
    }

    private ApiResponse<?> handle(Exception e, int expectedStatus) throws Exception {
        ResponseEntity<Object> res = handler.handleException(e, request());
        assertThat(res.getStatusCode().value()).isEqualTo(expectedStatus);
        assertThat(res.getBody()).isInstanceOf(ApiResponse.class);
        ApiResponse<?> body = (ApiResponse<?>) res.getBody();
        assertThat(body.success()).isFalse();
        assertThat(body.data()).isNull();
        return body;
    }

    private static ServletWebRequest request() {
        return new ServletWebRequest(new MockHttpServletRequest());
    }

    @SuppressWarnings("unused")
    private void target(String value) {
    }

    private static MethodParameter parameter() throws NoSuchMethodException {
        return new MethodParameter(GlobalExceptionHandlerTest.class.getDeclaredMethod("target", String.class), 0);
    }
}
