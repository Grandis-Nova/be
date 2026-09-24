package com.grandis.nova.preorder.outbox;

/** preorder 가 발행하는 이벤트 종류와 논리 목적지. 릴레이는 이 종류만 다시 보낸다. */
public enum OutboundEventType {

    REGISTER_JOB_READY("preorder-register"),
    SYNC_JOB_REPROCESS_REQUESTED("preorder-register"),
    CANCEL_JOB_READY("preorder-cancel"),
    PREORDER_CANCEL_REQUESTED("order-events");

    private final String destination;

    OutboundEventType(String destination) {
        this.destination = destination;
    }

    public String destination() {
        return destination;
    }
}
