package com.grandis.nova.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 요청마다 추적 ID 를 정한다. 로그(MDC)와 응답 봉투의 traceId, 응답 헤더가 같은 값을 쓴다.
 *
 * 앞단(queue-gateway · 다른 서비스)이 보낸 X-Request-Id 가 있으면 이어 쓴다 — 한 요청이 서비스를 건너도 같은 ID 로 찾을 수 있다.
 * 값은 클라이언트가 보낸 것이므로 모양을 검사한다. 로그에 줄바꿈·제어 문자를 넣어 로그를 위조하지 못하게 한다.
 *
 * 가장 먼저 돈다 — 보안 필터에서 401 이 나도 그 응답에 ID 가 있어야 한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolve(request.getHeader(HEADER));
        MDC.put(ApiResponse.TRACE_ID_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            // 스레드가 풀로 돌아가 다음 요청을 받는다. 지우지 않으면 남의 ID 가 섞인다.
            MDC.remove(ApiResponse.TRACE_ID_KEY);
        }
    }

    static String resolve(String presented) {
        if (presented != null && ALLOWED.matcher(presented).matches()) {
            return presented;
        }
        return UUID.randomUUID().toString();
    }
}
