package com.grandis.nova.common.web;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void 앞단이_보낸_ID_를_이어_쓰고_응답_헤더와_MDC_에_싣는다() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(RequestIdFilter.HEADER, "gw-abc_123.4");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();

        filter.doFilter(req, res, (rq, rs) -> seenInChain.set(MDC.get(ApiResponse.TRACE_ID_KEY)));

        assertThat(seenInChain.get()).isEqualTo("gw-abc_123.4");
        assertThat(res.getHeader(RequestIdFilter.HEADER)).isEqualTo("gw-abc_123.4");
    }

    @Test
    void 요청이_끝나면_MDC_를_비운다() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (rq, rs) -> { });

        assertThat(MDC.get(ApiResponse.TRACE_ID_KEY)).isNull();
    }

    @Test
    void 없거나_모양이_틀리면_새로_만든다() {
        assertThat(RequestIdFilter.resolve(null)).hasSize(36);
        assertThat(RequestIdFilter.resolve("bad\nvalue")).hasSize(36);       // 로그 위조 방지
        assertThat(RequestIdFilter.resolve("x".repeat(65))).hasSize(36);   // 너무 김
    }
}
