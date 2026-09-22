package com.grandis.nova.preorder.preorder;

import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import com.grandis.nova.preorder.support.ShopFixtures.PreorderProduct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static com.grandis.nova.preorder.preorder.PreorderStatus.CANCELED;
import static com.grandis.nova.preorder.preorder.PreorderStatus.CANCELING;
import static com.grandis.nova.preorder.preorder.PreorderStatus.PAYABLE;
import static com.grandis.nova.preorder.preorder.PreorderStatus.PENDING_SYNC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@PreorderIntegrationTest
@Transactional
class PreorderLedgerTest {

    @Autowired
    PreorderLedger ledger;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    EntityManager entityManager;

    ShopFixtures fixtures;
    PreorderProduct product;
    Long customerId;
    final AtomicLong nextPosition = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        product = fixtures.openPreorderProduct();
        customerId = fixtures.customer();
    }

    @Test
    void 접수하면_PENDING_SYNC_와_첫_이력이_남고_활성으로_표시된다() {
        Preorder preorder = ledger.accept(draft(customerId), EventActor.USER, null);

        assertThat(preorder.getId()).isNotNull();
        assertThat(row(preorder.getId()))
                .containsEntry("status", "PENDING_SYNC")
                .containsEntry("event_sequence", 1L)
                .containsEntry("active_marker", 1);
        assertThat(history(preorder.getId())).containsExactly("1:null>PENDING_SYNC:USER");
    }

    @Test
    void 전이가_성공하면_이력_번호가_하나_오르고_이력이_남는다() {
        Long id = ledger.accept(draft(customerId), EventActor.USER, null).getId();

        boolean changed = ledger.transition(id, PENDING_SYNC, CANCELING, EventActor.USER, null);

        assertThat(changed).isTrue();
        assertThat(row(id)).containsEntry("status", "CANCELING").containsEntry("event_sequence", 2L);
        assertThat(history(id)).containsExactly("1:null>PENDING_SYNC:USER", "2:PENDING_SYNC>CANCELING:USER");
    }

    @Test
    void 현재_상태가_기대와_다르면_아무것도_바꾸지_않는다() {
        Long id = ledger.accept(draft(customerId), EventActor.USER, null).getId();

        boolean changed = ledger.transition(id, PAYABLE, CANCELING, EventActor.USER, null);

        assertThat(changed).isFalse();
        assertThat(row(id)).containsEntry("status", "PENDING_SYNC").containsEntry("event_sequence", 1L);
        assertThat(history(id)).hasSize(1);
    }

    @Test
    void 허용하지_않는_전이는_호출_오류로_거부한다() {
        Long id = ledger.accept(draft(customerId), EventActor.USER, null).getId();

        assertThatThrownBy(() -> ledger.transition(id, CANCELED, PAYABLE, EventActor.SYSTEM, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger.transition(id, PENDING_SYNC, PAYABLE, EventActor.SYSTEM, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("markPayable");
        assertThat(row(id)).containsEntry("status", "PENDING_SYNC");
    }

    @Test
    void 결제_가능_반영은_한_번만_되고_기한_기준_시각을_찍는다() {
        Long id = ledger.accept(draft(customerId), EventActor.USER, null).getId();

        assertThat(ledger.markPayable(id, "EXT-1")).isTrue();
        Object payableFrom = row(id).get("payable_from");
        assertThat(ledger.markPayable(id, "EXT-2")).isFalse();

        assertThat(payableFrom).isNotNull();
        assertThat(row(id))
                .containsEntry("status", "PAYABLE")
                .containsEntry("external_reference", "EXT-1")
                .containsEntry("payable_from", payableFrom);
        assertThat(history(id)).containsExactly("1:null>PENDING_SYNC:USER", "2:PENDING_SYNC>PAYABLE:SYSTEM");
    }

    @Test
    void 취소_중인_예약에_늦게_온_등록_성공은_반영하지_않는다() {
        Long id = ledger.accept(draft(customerId), EventActor.USER, null).getId();
        ledger.transition(id, PENDING_SYNC, CANCELING, EventActor.USER, null);

        assertThat(ledger.markPayable(id, "EXT-LATE")).isFalse();

        assertThat(row(id)).containsEntry("status", "CANCELING").containsEntry("payable_from", null);
    }

    @Test
    void 취소_거절이면_CANCELING_에서_PAYABLE_로_되돌린다() {
        Long id = ledger.accept(draft(customerId), EventActor.USER, null).getId();
        ledger.markPayable(id, "EXT-1");
        ledger.transition(id, PAYABLE, CANCELING, EventActor.USER, null);

        boolean reverted = ledger.transition(id, CANCELING, PAYABLE, EventActor.SYSTEM, "SHIPPING_STARTED");

        assertThat(reverted).isTrue();
        assertThat(history(id)).last().isEqualTo("4:CANCELING>PAYABLE:SYSTEM");
    }

    @Test
    void 활성_예약이_있으면_같은_모델은_UNIQUE_로_막힌다() {
        ledger.accept(draft(customerId), EventActor.USER, null);

        assertThatThrownBy(() -> ledger.accept(draft(customerId), EventActor.USER, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_preorder_active");
    }

    @Test
    void 취소가_끝나면_활성_표식이_사라져_같은_모델을_다시_신청할_수_있다() {
        Long id = ledger.accept(draft(customerId), EventActor.USER, null).getId();
        ledger.transition(id, PENDING_SYNC, CANCELING, EventActor.USER, null);
        ledger.transition(id, CANCELING, CANCELED, EventActor.SYSTEM, null);

        Preorder again = ledger.accept(draft(customerId), EventActor.USER, null);

        assertThat(row(id)).containsEntry("status", "CANCELED").containsEntry("active_marker", null);
        assertThat(row(again.getId())).containsEntry("active_marker", 1);
    }

    @Test
    void 같은_입장권으로는_두_번_접수할_수_없다() {
        String ticket = "a".repeat(64);
        ledger.accept(draft(customerId, ticket), EventActor.USER, null);

        assertThatThrownBy(() -> ledger.accept(draft(fixtures.customer(), ticket), EventActor.USER, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_preorder_admission");
    }

    @Test
    void 관리자_전이는_사유가_없으면_거부하고_상태를_바꾸지_않는다() {
        Long id = ledger.accept(draft(customerId), EventActor.USER, null).getId();

        assertThatThrownBy(() -> ledger.transition(id, PENDING_SYNC, CANCELING, EventActor.ADMIN, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(row(id)).containsEntry("status", "PENDING_SYNC");
    }

    @Test
    void 관리자_대신_접수는_입장권_없이_사유와_함께_남는다() {
        Long id = ledger.accept(draft(customerId, null), EventActor.ADMIN, "전화 접수").getId();

        assertThat(row(id)).containsEntry("admission_ticket_id", null);
        assertThat(history(id)).containsExactly("1:null>PENDING_SYNC:ADMIN");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 트랜잭션_밖에서는_상태를_바꿀_수_없다() {
        assertThatThrownBy(() -> ledger.transition(1L, PENDING_SYNC, CANCELING, EventActor.USER, null))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private NewPreorder draft(Long customer) {
        return draft(customer, ShopFixtures.unique().replace("-", "") + "00000000000000000000000000000000");
    }

    private NewPreorder draft(Long customer, String admissionTicketId) {
        long position = nextPosition.getAndIncrement();
        return new NewPreorder(ShopFixtures.unique(), customer, product.productId(), product.optionId(),
                product.firstBatchId(), position, admissionTicketId, ShopFixtures.unique(),
                "Nova 1", "블랙 / 256GB", new BigDecimal("1250000"));
    }

    /** 이력은 커밋할 때 flush 된다. JDBC 로 읽기 전에 밀어 넣는다. */
    private Map<String, Object> row(Long id) {
        entityManager.flush();
        return jdbcTemplate.queryForMap("""
                SELECT status, event_sequence, active_marker, payable_from, external_reference, admission_ticket_id
                  FROM preorders WHERE id = ?
                """, id);
    }

    /** "번호:from>to:actor" 목록. 번호 순. */
    private List<String> history(Long id) {
        entityManager.flush();
        return jdbcTemplate.query("""
                SELECT event_sequence, from_status, to_status, actor
                  FROM preorder_events WHERE preorder_id = ? ORDER BY event_sequence
                """, (rs, n) -> rs.getLong(1) + ":" + rs.getString(2) + ">" + rs.getString(3) + ":" + rs.getString(4),
                id);
    }
}
