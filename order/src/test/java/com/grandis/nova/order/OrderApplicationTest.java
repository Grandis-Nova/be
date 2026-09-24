package com.grandis.nova.order;

import com.grandis.nova.order.config.TransactionIsolationVerifier;
import com.grandis.nova.order.support.OrderIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@OrderIntegrationTest
class OrderApplicationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ApplicationContext context;

    @Test
    void contextLoadsWithReadCommittedIsolation() {
        assertThat(jdbcTemplate.queryForObject("SELECT @@transaction_isolation", String.class))
                .isEqualTo("READ-COMMITTED");
    }

    // 빈이 빠지면 기동 때 격리 수준을 아무도 확인하지 않는다.
    @Test
    void isolationVerifierIsRegistered() {
        assertThat(context.getBeansOfType(TransactionIsolationVerifier.class)).hasSize(1);
    }

    @Test
    void migrationsAreApplied() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'shop'", String.class);
        Integer failed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0", Integer.class);

        assertThat(tables).contains("orders", "order_items", "order_events", "payments", "payment_transactions",
                "cart_items", "option_inventories", "outbox_events", "flyway_schema_history");
        assertThat(failed).isZero();
    }
}
