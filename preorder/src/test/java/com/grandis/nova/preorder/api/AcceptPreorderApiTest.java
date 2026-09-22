package com.grandis.nova.preorder.api;

import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.preorder.catalog.CatalogClient;
import com.grandis.nova.preorder.catalog.ProductCatalog;
import com.grandis.nova.preorder.support.AdmissionTickets;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import com.grandis.nova.preorder.support.ShopFixtures.PreorderProduct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@PreorderIntegrationTest
@AutoConfigureMockMvc
class AcceptPreorderApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    CatalogClient catalogClient;

    ShopFixtures fixtures;
    PreorderProduct product;
    Long customerId;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        product = fixtures.openPreorderProduct();
        customerId = fixtures.customer();
        catalogReturns(product.productId(), "PREORDER", "ACTIVE", product.optionId(), "ACTIVE");
    }

    @Test
    void 접수하면_202_와_순번_차수를_돌려주고_작업과_아웃박스를_같이_남긴다() throws Exception {
        String ticket = ticket(product.productId(), customerId);

        String preorderId = accept(customerId, product.productId(), product.optionId(), "key-00000001", ticket)
                .andExpect(status().isAccepted())
                .andExpect(header().string("X-Idempotent-Replay", "false"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PENDING_SYNC"))
                .andExpect(jsonPath("$.data.queuePosition").value(1))
                .andExpect(jsonPath("$.data.shipmentBatch.batchNumber").value(1))
                .andExpect(jsonPath("$.data.shipmentBatch.positionTo").value(ShopFixtures.FIRST_BATCH_LAST_POSITION))
                .andExpect(jsonPath("$.data.replayed").value(false))
                .andReturn().getResponse().getHeader("Location").replace("/api/v1/preorders/", "");

        Map<String, Object> preorder = jdbcTemplate.queryForMap("""
                SELECT id, status, admission_ticket_id, unit_price_snapshot FROM preorders WHERE preorder_token = ?
                """, preorderId);
        assertThat(preorder).containsEntry("status", "PENDING_SYNC")
                .containsEntry("admission_ticket_id", sha256(ticket));
        Map<String, Object> job = jdbcTemplate.queryForMap("""
                SELECT id, job_type, status,
                       JSON_UNQUOTE(JSON_EXTRACT(request_payload, '$.ourReservationId')) AS reservation_id,
                       JSON_UNQUOTE(JSON_EXTRACT(request_payload, '$.optionCode')) AS option_code
                  FROM preorder_sync_jobs WHERE preorder_id = ?
                """, preorder.get("id"));
        assertThat(job).containsEntry("job_type", "REGISTER").containsEntry("status", "PENDING")
                .containsEntry("reservation_id", preorderId)
                .containsEntry("option_code", "SKU-" + product.optionId());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT event_type FROM outbox_events WHERE aggregate_type = 'PREORDER_SYNC_JOB' AND aggregate_id = ?
                """, String.class, job.get("id"))).isEqualTo("REGISTER_JOB_READY");
    }

    @Test
    void 같은_키로_다시_보내면_기존_예약을_돌려주고_순번을_쓰지_않는다() throws Exception {
        String ticket = ticket(product.productId(), customerId);
        String first = body(accept(customerId, product.productId(), product.optionId(), "key-replay-1", ticket));

        accept(customerId, product.productId(), product.optionId(), "key-replay-1", ticket)
                .andExpect(status().isAccepted())
                .andExpect(header().string("X-Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.data.preorderId").value(first))
                .andExpect(jsonPath("$.data.replayed").value(true));

        assertThat(nextQueuePosition()).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM preorders WHERE customer_id = ?", customerId)).isEqualTo(1);
    }

    @Test
    void 같은_키에_다른_옵션이면_422_와_다른_필드를_알린다() throws Exception {
        Long otherOption = fixtures.option(product.productId(), "ACTIVE");
        catalogReturnsTwoOptions(product.productId(), product.optionId(), otherOption);
        String ticket = ticket(product.productId(), customerId);
        accept(customerId, product.productId(), product.optionId(), "key-mismatch", ticket);

        accept(customerId, product.productId(), otherOption, "key-mismatch", ticket)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("KEY_PAYLOAD_MISMATCH"))
                .andExpect(jsonPath("$.error.details.fields[0]").value("optionId"));
    }

    @Test
    void 입장권이_없으면_400_무효면_403() throws Exception {
        accept(customerId, product.productId(), product.optionId(), "key-no-ticket", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ADMISSION_TICKET_REQUIRED"));
        accept(customerId, product.productId(), product.optionId(), "key-bad-ticket", "et_forged.sig")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ADMISSION_TICKET_INVALID"));
        accept(customerId, product.productId(), product.optionId(), "key-other-ticket",
                ticket(product.productId(), fixtures.customer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ADMISSION_TICKET_INVALID"));
    }

    @Test
    void 본문_필드가_비면_400_과_필드_이름을_알린다() throws Exception {
        mockMvc.perform(post("/api/v1/preorders").param("productId", product.productId().toString())
                        .header("Idempotency-Key", "key-no-option")
                        .header("X-Admission-Ticket", ticket(product.productId(), customerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":%d}".formatted(product.productId()))
                        .with(user(customerId.toString()).roles("USER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value("optionId"));
    }

    @Test
    void 쿼리와_본문의_상품이_다르면_400() throws Exception {
        mockMvc.perform(request(customerId, product.productId() + 1, product.productId(), product.optionId(),
                        "key-query-body", ticket(product.productId(), customerId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 접수_키가_없으면_400_IDEMPOTENCY_KEY_REQUIRED() throws Exception {
        mockMvc.perform(post("/api/v1/preorders").param("productId", product.productId().toString())
                        .header("X-Admission-Ticket", ticket(product.productId(), customerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(product.productId(), product.optionId()))
                        .with(user(customerId.toString()).roles("USER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void 접수_키가_8자보다_짧거나_64자보다_길면_400() throws Exception {
        String ticket = ticket(product.productId(), customerId);

        accept(customerId, product.productId(), product.optionId(), "short", ticket)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value("Idempotency-Key"));
        accept(customerId, product.productId(), product.optionId(), "k".repeat(65), ticket)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        assertThat(count("SELECT COUNT(*) FROM preorders WHERE customer_id = ?", customerId)).isZero();
    }

    @Test
    void 관리자_대신_접수에_사유가_없으면_400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/preorders")
                        .header("Idempotency-Key", "key-admin-noreason")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":%d,"optionId":%d,"customerId":%d,"reason":" "}
                                """.formatted(product.productId(), product.optionId(), customerId))
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value("reason"));
    }

    @Test
    void 오픈_전이면_SALE_NOT_OPEN_마감_뒤면_SALE_CLOSED() throws Exception {
        PreorderProduct notOpen = fixtures.preorderProduct(Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200));
        catalogReturns(notOpen.productId(), "PREORDER", "ACTIVE", notOpen.optionId(), "ACTIVE");
        PreorderProduct closed = fixtures.preorderProduct(Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));
        catalogReturns(closed.productId(), "PREORDER", "ACTIVE", closed.optionId(), "ACTIVE");

        accept(customerId, notOpen.productId(), notOpen.optionId(), "key-not-open", ticket(notOpen.productId(), customerId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SALE_NOT_OPEN"));
        accept(customerId, closed.productId(), closed.optionId(), "key-closed", ticket(closed.productId(), customerId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SALE_CLOSED"));
    }

    @Test
    void 없는_상품_판매_중지_옵션_다른_상품의_옵션은_404() throws Exception {
        Long missing = fixtures.product("PREORDER", "ACTIVE");
        given(catalogClient.getProduct(missing)).willThrow(
                HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null));
        accept(customerId, missing, 1L, "key-missing", ticket(missing, customerId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PRODUCT_NOT_FOUND"));

        PreorderProduct paused = fixtures.openPreorderProduct();
        catalogReturns(paused.productId(), "PREORDER", "ACTIVE", paused.optionId(), "PAUSED");
        accept(customerId, paused.productId(), paused.optionId(), "key-paused", ticket(paused.productId(), customerId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PRODUCT_OPTION_NOT_FOUND"));

        accept(customerId, product.productId(), paused.optionId(), "key-foreign-option",
                ticket(product.productId(), customerId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PRODUCT_OPTION_NOT_FOUND"));
    }

    @Test
    void 같은_모델에_진행_중_예약이_있으면_409_와_기존_예약을_알린다() throws Exception {
        String first = body(accept(customerId, product.productId(), product.optionId(), "key-active-1",
                ticket(product.productId(), customerId)));
        // 다른 창(30초 전)에 발급된 다른 입장권 — 입장권 UNIQUE 가 아니라 활성 예약 UNIQUE 에 걸리게 한다
        String otherTicket = AdmissionTickets.issue(product.productId(), customerId, Instant.now().minusSeconds(30));

        accept(customerId, product.productId(), product.optionId(), "key-active-2", otherTicket)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ACTIVE_PREORDER_EXISTS"))
                .andExpect(jsonPath("$.error.details.existingPreorderId").value(first));
        assertThat(nextQueuePosition()).as("실패한 접수의 순번은 롤백된다").isEqualTo(2);
    }

    @Test
    void 이미_쓴_입장권이면_409_ADMISSION_TICKET_USED() throws Exception {
        String ticket = ticket(product.productId(), customerId);
        String first = body(accept(customerId, product.productId(), product.optionId(), "key-used-1", ticket));
        // 활성 예약 UNIQUE 를 비켜 입장권 UNIQUE 만 남긴다
        jdbcTemplate.update("UPDATE preorders SET status = 'CANCELED' WHERE preorder_token = ?", first);

        accept(customerId, product.productId(), product.optionId(), "key-used-2", ticket)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ADMISSION_TICKET_USED"));
    }

    @Test
    void 순번이_첫_차수를_넘으면_다음_차수에_배정된다() throws Exception {
        jdbcTemplate.update("UPDATE preorder_campaigns SET next_queue_position = ? WHERE product_id = ?",
                ShopFixtures.FIRST_BATCH_LAST_POSITION + 1, product.productId());

        accept(customerId, product.productId(), product.optionId(), "key-batch-2", ticket(product.productId(), customerId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.queuePosition").value(ShopFixtures.FIRST_BATCH_LAST_POSITION + 1))
                .andExpect(jsonPath("$.data.shipmentBatch.batchNumber").value(2))
                .andExpect(jsonPath("$.data.shipmentBatch.positionTo").doesNotExist());
    }

    @Test
    void 로그인하지_않으면_401_관리자_토큰이면_403() throws Exception {
        mockMvc.perform(post("/api/v1/preorders").param("productId", product.productId().toString())
                        .header("Idempotency-Key", "key-anonymous")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(product.productId(), product.optionId())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(request(customerId, product.productId(), product.productId(), product.optionId(),
                        "key-admin-user-api", ticket(product.productId(), customerId))
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void 관리자_대신_접수는_입장권_없이_사유와_메모를_남긴다() throws Exception {
        String preorderId = body(mockMvc.perform(adminRequest(customerId, "key-admin-0001", "전화 접수"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PENDING_SYNC")));

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT p.admission_ticket_id, p.internal_note, e.actor, e.reason
                  FROM preorders p JOIN preorder_events e ON e.preorder_id = p.id AND e.event_sequence = 1
                 WHERE p.preorder_token = ?
                """, preorderId);
        assertThat(row).containsEntry("admission_ticket_id", null)
                .containsEntry("internal_note", "VIP")
                .containsEntry("actor", "ADMIN")
                .containsEntry("reason", "전화 접수");
    }

    @Test
    void 관리자_대신_접수에서_없는_회원이면_404_사용자는_403() throws Exception {
        mockMvc.perform(adminRequest(Long.MAX_VALUE, "key-admin-0002", "전화 접수"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("MEMBER_NOT_FOUND"));
        mockMvc.perform(adminRequest(customerId, "key-admin-0003", "전화 접수")
                        .with(user(customerId.toString()).roles("USER")))
                .andExpect(status().isForbidden());
    }

    private ResultActions accept(Long customer, Long productId, Long optionId, String key, String ticket)
            throws Exception {
        return mockMvc.perform(request(customer, productId, productId, optionId, key, ticket));
    }

    private MockHttpServletRequestBuilder request(Long customer, Long queryProductId, Long productId, Long optionId,
                                                  String key, String ticket) {
        MockHttpServletRequestBuilder builder = post("/api/v1/preorders")
                .param("productId", queryProductId.toString())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(productId, optionId))
                .with(user(customer.toString()).roles("USER"));
        return ticket == null ? builder : builder.header("X-Admission-Ticket", ticket);
    }

    private MockHttpServletRequestBuilder adminRequest(Long customer, String key, String reason) {
        return post("/api/v1/admin/preorders")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"productId":%d,"optionId":%d,"customerId":%d,"reason":"%s","internalNote":"VIP"}
                        """.formatted(product.productId(), product.optionId(), customer, reason))
                .with(user("admin").roles("ADMIN"));
    }

    private static String json(Long productId, Long optionId) {
        return "{\"productId\":%d,\"optionId\":%d}".formatted(productId, optionId);
    }

    private static String body(ResultActions result) throws Exception {
        String location = result.andExpect(status().isAccepted()).andReturn().getResponse().getHeader("Location");
        return location.replace("/api/v1/preorders/", "");
    }

    private static String ticket(Long productId, Long customer) {
        return AdmissionTickets.issue(productId, customer, Instant.now());
    }

    private void catalogReturns(Long productId, String saleMode, String status, Long optionId, String optionStatus) {
        given(catalogClient.getProduct(productId)).willReturn(ApiResponse.ok(new ProductCatalog(productId, "Nova 1",
                saleMode, status, List.of(option(optionId, optionStatus)))));
    }

    private void catalogReturnsTwoOptions(Long productId, Long optionId, Long otherOptionId) {
        given(catalogClient.getProduct(productId)).willReturn(ApiResponse.ok(new ProductCatalog(productId, "Nova 1",
                "PREORDER", "ACTIVE", List.of(option(optionId, "ACTIVE"), option(otherOptionId, "ACTIVE")))));
    }

    private static ProductCatalog.Option option(Long optionId, String status) {
        return new ProductCatalog.Option(optionId, "SKU-" + optionId, "블랙 / 256GB", new BigDecimal("1250000"), status);
    }

    private long nextQueuePosition() {
        return jdbcTemplate.queryForObject("SELECT next_queue_position FROM preorder_campaigns WHERE product_id = ?",
                Long.class, product.productId());
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    private static String sha256(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    }
}
