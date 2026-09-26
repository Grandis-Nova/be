package com.grandis.nova.order.outbox;

/**
 * order 가 발행하는 이벤트 종류와 논리 목적지. 공유 표(outbox_events)에서 order 의 행을 가르는 기준이다 —
 * preorder 릴레이는 자기 종류만 집으므로, order 의 행은 order 쪽 발행기가 이 종류로 골라 보낸다.
 */
public enum OutboundEventType {

    PREORDER_ORDER_SETTLED("preorder-events");

    private final String destination;

    OutboundEventType(String destination) {
        this.destination = destination;
    }

    public String destination() {
        return destination;
    }
}
