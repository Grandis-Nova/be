package com.grandis.nova.common.web;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.ErrorCode;
import com.grandis.nova.common.Retryable;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * HTTP 서비스 넷이 공유한다. 응답은 전부 ApiResponse 봉투다(api-spec 공통 규칙).
 *
 * 내부 오류 원문을 응답에 담지 않는다(FR-U-04). 원인은 로그로만 남기고 사용자에게는 ErrorCode 의 문구를 보낸다.
 * 401·403 은 여기까지 오지 않는다 — 보안 필터 체인의 entry point / access denied handler 가 같은 봉투로 낸다.
 * 단, 컨트롤러 안에서 던진 BusinessException(FORBIDDEN) 은 여기로 온다(예: @CurrentCustomerId 에 관리자 토큰).
 *
 * 실측(SecurityChainTest.malformedJsonIs400Envelope·unknownUrlIs404Envelope): @ExceptionHandler(Exception.class) 는 MVC 기본
 * 오류 변환(DefaultHandlerExceptionResolver)보다 먼저 매치되어, 깨진 JSON·없는 URL·405·415 가 전부 500 으로 나갔다.
 * 그래서 스프링이 상태를 아는 예외(ErrorResponse 구현체)는 그 상태로, 본문 파싱 실패는 400 으로 먼저 가른다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        ErrorCode code = e.errorCode();
        return ResponseEntity.status(code.status())
                .body(ApiResponse.error(code, e.getMessage(), null, e instanceof Retryable));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        // api-spec 예시: error.detail = {"field": "quantity"}. 여러 필드면 필드→사유 맵.
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> fieldErrors.putIfAbsent(f.getField(), f.getDefaultMessage()));
        ErrorCode code = CommonErrorCode.VALIDATION_FAILED;
        return ResponseEntity.status(code.status()).body(ApiResponse.error(code, code.defaultMessage(), fieldErrors, false));
    }

    /** 본문이 JSON 이 아니거나 타입이 안 맞는다. api-spec: "잘못된 타입은 400". */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(400).body(ApiResponse.error(CommonErrorCode.VALIDATION_FAILED));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        if (e instanceof ErrorResponse spring) {
            // 없는 URL(404)·메서드(405)·미디어 타입(415)·파라미터 누락/타입(400) 등. 스프링이 정한 상태를 그대로 쓰고 봉투만 입힌다.
            int status = spring.getStatusCode().value();
            ErrorCode code = switch (status) {
                case 400 -> CommonErrorCode.VALIDATION_FAILED;
                case 404 -> CommonErrorCode.RESOURCE_NOT_FOUND;
                case 405 -> CommonErrorCode.METHOD_NOT_ALLOWED;
                case 415 -> CommonErrorCode.UNSUPPORTED_MEDIA_TYPE;
                default -> status >= 500 ? CommonErrorCode.INTERNAL_ERROR : CommonErrorCode.VALIDATION_FAILED;
            };
            return ResponseEntity.status(status).body(ApiResponse.error(code));
        }
        // 예상하지 못한 오류만 여기 온다. 원문은 로그에만 남긴다.
        // api-spec: 500 은 "처리 결과 불명일 수 있음 — 성공·미생성을 단정하지 말고 해당 키로 확인". 재전송이 안전한지 알 수 없어 retryable=false 로 낸다.
        log.error("처리되지 않은 예외", e);
        ErrorCode code = CommonErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(code.status()).body(ApiResponse.error(code));
    }
}
