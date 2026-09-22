package com.grandis.nova.preorder.outbox;

/**
 * 아웃박스에 메시지를 적었다는 애플리케이션 이벤트.
 *
 * 발행기는 이것을 {@code @TransactionalEventListener(phase = AFTER_COMMIT)} 로 받아 커밋 직후 SQS 로 보낸다.
 * 트랜잭션이 롤백되면 전달되지 않으므로 롤백된 메시지는 나가지 않는다.
 * 커밋과 발행 사이에 프로세스가 죽으면 이 이벤트는 사라진다 — 그 행은 미발행으로 남아 릴레이가 다시 보낸다.
 */
public record OutboxAppended(Long outboxEventId) {
}
