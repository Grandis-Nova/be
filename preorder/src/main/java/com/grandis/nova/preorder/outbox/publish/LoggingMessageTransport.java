package com.grandis.nova.preorder.outbox.publish;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 로그로만 남기는 기본 전송. 큐 없이도 발행 흐름이 끝까지 돈다. 메시지마다라 DEBUG, 본문은 TRACE. */
@Component
@ConditionalOnProperty(name = "nova.outbox.transport", havingValue = "log", matchIfMissing = true)
class LoggingMessageTransport implements MessageTransport {

    private static final Logger log = LoggerFactory.getLogger(LoggingMessageTransport.class);

    @Override
    public void send(OutboundMessage message) {
        log.debug("메시지 발행 destination={} eventType={} eventId={}",
                message.destination(), message.eventType(), message.eventId());
        log.trace("메시지 본문 eventId={} body={}", message.eventId(), message.body());
    }
}
