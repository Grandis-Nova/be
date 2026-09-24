package com.grandis.nova.preorder.outbox.publish;

import com.grandis.nova.preorder.outbox.EventEnvelope;
import com.grandis.nova.preorder.outbox.OutboundEventType;
import com.grandis.nova.preorder.outbox.OutboxEvent;
import com.grandis.nova.preorder.outbox.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

/**
 * 아웃박스 행을 봉투로 싸서 보내고 결과를 행에 남긴다. 커밋 직후 발행과 릴레이가 함께 쓴다.
 * 전송 실패는 삼킨다 — 행이 미발행으로 남아 릴레이가 다시 보낸다.
 */
@Component
class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEvents;
    private final MessageTransport transport;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    OutboxPublisher(OutboxEventRepository outboxEvents, MessageTransport transport, JsonMapper jsonMapper,
                    Clock clock) {
        this.outboxEvents = outboxEvents;
        this.transport = transport;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /** 이미 발행된 행(릴레이가 먼저 보냄)은 건너뛴다. */
    void publishById(Long outboxEventId) {
        outboxEvents.findById(outboxEventId)
                .filter(event -> event.getPublishedAt() == null)
                .ifPresent(this::publish);
    }

    /** @return 보냈으면 true */
    boolean publish(OutboxEvent event) {
        try {
            transport.send(toMessage(event));
        } catch (RuntimeException e) {
            outboxEvents.recordFailure(event.getId());
            log.warn("아웃박스 발행 실패 — 릴레이가 다시 보낸다 outboxEventId={} eventType={}",
                    event.getId(), event.getEventType(), e);
            return false;
        }
        outboxEvents.markPublished(event.getId(), clock.instant());
        return true;
    }

    private OutboundMessage toMessage(OutboxEvent event) {
        OutboundEventType type = OutboundEventType.valueOf(event.getEventType());
        EventEnvelope envelope = new EventEnvelope(event.getEventId(), event.getEventType(), event.getAggregateType(),
                event.getAggregateId(), event.getCreatedAt(), jsonMapper.readTree(event.getPayload()));
        return new OutboundMessage(type.destination(), event.getEventId(), event.getEventType(),
                jsonMapper.writeValueAsString(envelope));
    }
}
