package com.grandis.nova.preorder.event;

import com.grandis.nova.preorder.accept.AcceptResult;
import com.grandis.nova.preorder.accept.PreorderAcceptService;
import com.grandis.nova.preorder.catalog.CatalogClient;
import com.grandis.nova.preorder.support.AcceptFixtures;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.core.JacksonException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 큐 메시지 본문(계약 2.0 공통 봉투)을 종류별 처리로 보내는지. 본문은 계약 예시 모양 그대로다. */
@PreorderIntegrationTest
class PreorderEventDispatcherTest {

    @Autowired
    PreorderEventDispatcher dispatcher;

    @Autowired
    PreorderAcceptService acceptService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    CatalogClient catalogClient;

    ShopFixtures fixtures;
    Long preorderId;
    String token;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        AcceptResult accepted = new AcceptFixtures(acceptService, fixtures, catalogClient).accept(fixtures.customer());
        preorderId = accepted.preorder().getId();
        token = AcceptFixtures.tokenOf(accepted);
    }

    @Test
    void 외부_작업_성공_메시지를_등록_반영으로_보낸다() {
        Long jobId = fixtures.workerSucceeds(preorderId, "REGISTER");
        String externalNumber = "R-" + ShopFixtures.unique();

        dispatcher.dispatch("""
                {"eventId":"%s","eventType":"EXTERNAL_JOB_SUCCEEDED","aggregateType":"PREORDER_SYNC_JOB",
                 "aggregateId":%d,"occurredAt":"2026-09-03T01:00:03.470Z",
                 "payload":{"syncJobId":%d,"preorderId":"%s","jobType":"REGISTER","externalNumber":"%s"}}
                """.formatted(ShopFixtures.unique(), jobId, jobId, token, externalNumber));

        assertThat(jdbcTemplate.queryForObject("SELECT external_reference FROM preorders WHERE id = ?",
                String.class, preorderId)).isEqualTo(externalNumber);
    }

    @Test
    void 주문_정리_메시지를_정리_결과_처리로_보낸다() {
        dispatcher.dispatch("""
                {"eventId":"%s","eventType":"PREORDER_ORDER_SETTLED","aggregateType":"PREORDER",
                 "aggregateId":%d,"occurredAt":"2026-09-03T01:00:03.470Z",
                 "payload":{"preorderId":"%s","result":"NO_ORDER","reason":null}}
                """.formatted(ShopFixtures.unique(), preorderId, token));

        // 취소 중이 아니므로 아무것도 만들지 않는다 — 결과 문자열이 enum 으로 읽혔는지만 본다
        assertThat(fixtures.count(
                "SELECT COUNT(*) FROM preorder_sync_jobs WHERE preorder_id = ? AND job_type = 'CANCEL'", preorderId))
                .isZero();
    }

    @Test
    void 받지_않는_이벤트_종류는_조용히_버리지_않고_예외로_올린다() {
        assertThatThrownBy(() -> dispatcher.dispatch("""
                {"eventId":"e-1","eventType":"SOMETHING_ELSE","aggregateType":"PREORDER","aggregateId":1,
                 "payload":{}}
                """)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 깨진_본문이나_모르는_결과_값은_예외로_올린다() {
        assertThatThrownBy(() -> dispatcher.dispatch("not-json")).isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> dispatcher.dispatch("""
                {"eventId":"e-2","eventType":"PREORDER_ORDER_SETTLED","aggregateType":"PREORDER","aggregateId":1,
                 "payload":{"preorderId":"%s","result":"MAYBE","reason":null}}
                """.formatted(token))).isInstanceOf(JacksonException.class);
    }
}
