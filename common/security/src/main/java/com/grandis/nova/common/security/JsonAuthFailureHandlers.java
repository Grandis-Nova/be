package com.grandis.nova.common.security;

import tools.jackson.databind.ObjectMapper;   // Boot 4 = Jackson 3 (tools.jackson)
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * 401 과 403 을 api-spec 봉투로 쓴다. 원본은 두 클래스가 같은 본문 작성 함수를 각자 갖고 있었다(03 ⑫). 하나로 합쳤다.
 * 401 UNAUTHENTICATED("세션 없음·만료"), 403 FORBIDDEN("권한 부족"). 이유(필터의 ATTR_FAILURE_REASON)는 응답에 내지 않는다.
 * 폐기 조회 실패로 닫힌 401 만 retryable=true 다(D-2).
 */
public final class JsonAuthFailureHandlers {

    private final ObjectMapper objectMapper;

    public JsonAuthFailureHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AuthenticationEntryPoint entryPoint() {
        return (request, response, ex) -> {
            boolean retryable = Boolean.TRUE.equals(request.getAttribute(JwtAuthenticationFilter.ATTR_RETRYABLE));
            write(response, 401, ApiResponse.error(CommonErrorCode.UNAUTHENTICATED,
                    CommonErrorCode.UNAUTHENTICATED.defaultMessage(), null, retryable));
        };
    }

    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, ex) -> write(response, 403, ApiResponse.error(CommonErrorCode.FORBIDDEN));
    }

    private void write(HttpServletResponse response, int status, ApiResponse<Void> body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
