package com.grandis.nova.order.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

/** 기록만 한다. 발행 완료 · 실패 표시와 릴레이 조회는 발행기를 붙일 때 더한다. */
interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
}
