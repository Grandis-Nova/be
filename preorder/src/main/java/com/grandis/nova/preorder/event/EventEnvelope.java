package com.grandis.nova.preorder.event;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 큐 메시지 본문(계약 preorder-internal.md 2.0 공통 봉투). payload 는 이벤트 종류마다 모양이 달라 트리로 받는다.
 *
 * @param eventId 발행한 아웃박스 행의 event_id. 같은 메시지가 두 번 올 수 있다
 */
public record EventEnvelope(
        String eventId,
        String eventType,
        String aggregateType,
        Long aggregateId,
        Instant occurredAt,
        JsonNode payload
) {
}
