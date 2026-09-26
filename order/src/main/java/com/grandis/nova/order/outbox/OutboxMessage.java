package com.grandis.nova.order.outbox;

/**
 * order 가 발행하는 메시지.
 *
 * record 의 칸이 곧 payload 다. 이벤트 종류 · aggregate 를 record 가 함께 정하므로
 * 종류와 본문이 어긋난 메시지를 만들 수 없다. payload 에는 받는 쪽 계약에 있는 칸만 담고 민감정보는 넣지 않는다.
 */
public sealed interface OutboxMessage permits PreorderOrderSettled {

    OutboundEventType eventType();

    AggregateType aggregateType();

    Long aggregateId();
}
