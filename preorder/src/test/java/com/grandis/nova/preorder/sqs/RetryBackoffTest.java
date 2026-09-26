package com.grandis.nova.preorder.sqs;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RetryBackoffTest {

    static final Duration BASE = Duration.ofSeconds(5);
    static final Duration MAX = Duration.ofMinutes(5);

    @Test
    void 실패할수록_두_배로_늘고_절반_이상_전체_이하에서_흩어진다() {
        assertThat(RetryBackoff.of(1, BASE, MAX)).isBetween(Duration.ofMillis(2500), Duration.ofSeconds(5));
        assertThat(RetryBackoff.of(2, BASE, MAX)).isBetween(Duration.ofSeconds(5), Duration.ofSeconds(10));
        assertThat(RetryBackoff.of(3, BASE, MAX)).isBetween(Duration.ofSeconds(10), Duration.ofSeconds(20));
    }

    @Test
    void 상한을_넘지_않고_큰_횟수에서도_넘치지_않는다() {
        assertThat(RetryBackoff.of(10, BASE, MAX)).isBetween(Duration.ofMillis(150_000), MAX);
        assertThat(RetryBackoff.of(Integer.MAX_VALUE, BASE, MAX)).isBetween(Duration.ofMillis(150_000), MAX);
    }
}
