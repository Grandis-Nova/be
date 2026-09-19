package com.grandis.nova.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청 하나의 로그를 묶는 식별자를 MDC 에 넣는다. api-spec 봉투의 traceId 와 같은 값이다(원본의 requestId 를 이름만 바꿨다).
 * 응답 헤더 X-Trace-Id 로도 내보내 프론트가 장애 신고 때 붙일 수 있게 한다. 헤더 이름은 api-spec 에 없어 이쪽에서 정했다.
 *
 * 보안 필터 체인보다 먼저 돌아야 401·403 봉투에도 traceId 가 실린다(HIGHEST_PRECEDENCE).
 * 인증 필터가 subject 를 MDC 에 더 넣고, 정리는 여기서 한 번에 한다 — 스레드 풀 재사용 시 다음 요청으로 새지 않게.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcContextFilter extends OncePerRequestFilter {

    public static final String TRACE_ID = "traceId";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = resolveTraceId(request);
        MDC.put(TRACE_ID, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }

    /** 클라이언트 값은 MDC 를 거쳐 로그 패턴에 전개된다. 개행·제어문자가 들어오면 로그 한 줄이 여러 줄로 위조되므로 문자 집합으로 거른다. */
    private static final java.util.regex.Pattern SAFE_TRACE_ID = java.util.regex.Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private static String resolveTraceId(HttpServletRequest request) {
        String incoming = request.getHeader(TRACE_ID_HEADER);
        if (incoming == null || !SAFE_TRACE_ID.matcher(incoming).matches()) {
            return UUID.randomUUID().toString();
        }
        return incoming;
    }
}
