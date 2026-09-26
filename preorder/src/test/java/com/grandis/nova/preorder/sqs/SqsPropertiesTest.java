package com.grandis.nova.preorder.sqs;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqsPropertiesTest {

    @Test
    void 소비기_설정이_SQS_가_받는_범위_밖이면_기동하지_않는다() {
        assertThatCode(() -> consumer(10, 20, Duration.ofMinutes(5))).doesNotThrowAnyException();
        assertThatThrownBy(() -> consumer(11, 20, Duration.ofMinutes(5))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consumer(10, 21, Duration.ofMinutes(5))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consumer(10, 20, Duration.ofHours(13))).isInstanceOf(IllegalArgumentException.class);
    }

    private static SqsProperties.Consumer consumer(int maxMessages, int waitSeconds, Duration visibility) {
        return new SqsProperties.Consumer(true, "preorder-events", 1, waitSeconds, maxMessages, visibility,
                Duration.ofSeconds(5), Duration.ofMinutes(5));
    }
}
