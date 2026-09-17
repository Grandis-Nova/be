package com.grandis.nova.common.web;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * HTTP 서비스 넷이 공유한다.
 *
 * 내부 오류 원문을 응답에 담지 않는다(FR-U-04). 원인은 로그로만 남기고
 * 사용자에게는 ErrorCode 의 문구를 보낸다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        ErrorCode code = e.errorCode();
        return ResponseEntity.status(code.status())
                .body(ErrorResponse.of(code, e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        List<ErrorResponse.FieldError> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ErrorResponse.FieldError(f.getField(), f.getDefaultMessage()))
                .toList();
        ErrorCode code = CommonErrorCode.INVALID_REQUEST;
        return ResponseEntity.status(code.status())
                .body(ErrorResponse.of(code, code.defaultMessage(), fieldErrors));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        // 예상하지 못한 오류만 여기 온다. 원문은 로그에만 남긴다.
        log.error("처리되지 않은 예외", e);
        ErrorCode code = CommonErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(code.status())
                .body(ErrorResponse.of(code, code.defaultMessage()));
    }
}
