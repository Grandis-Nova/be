package com.grandis.nova.preorder.sqs;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/** 실패한 메시지를 다시 보이게 할 때까지의 대기. 지수로 늘리되 상한을 두고, 절반은 무작위로 흩어 한꺼번에 몰리지 않게 한다. */
final class RetryBackoff {

    private RetryBackoff() {
    }

    /** @param receiveCount 이번까지 받은 횟수(1부터) */
    static Duration of(int receiveCount, Duration base, Duration max) {
        long exponent = Math.min(Math.max(receiveCount - 1, 0), 30);
        long capped = Math.min(max.toMillis(), base.toMillis() << exponent);
        long half = capped / 2;
        return Duration.ofMillis(half + ThreadLocalRandom.current().nextLong(half + 1));
    }
}
