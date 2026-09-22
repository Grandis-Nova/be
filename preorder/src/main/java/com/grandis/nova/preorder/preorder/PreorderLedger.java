package com.grandis.nova.preorder.preorder;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * 예약 상태를 바꾸는 유일한 길. 상태 변경 · 이력 번호 증가 · 이력 INSERT 를 한 트랜잭션에서 한다.
 * 이력 없이 상태만 바뀌는 경로를 만들지 않으려고 전이를 여기로 모았다.
 *
 * 스스로 트랜잭션을 열지 않는다(MANDATORY). 전이는 늘 다른 변경(작업 행 · 아웃박스)과 한 트랜잭션이어야 해서,
 * 여기서 따로 커밋되면 그 원자성이 깨진다.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class PreorderLedger {

    private final PreorderRepository preorders;
    private final EntityManager entityManager;
    private final Clock clock;

    public PreorderLedger(PreorderRepository preorders, EntityManager entityManager, Clock clock) {
        this.preorders = preorders;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /**
     * 새 예약과 첫 이력(번호 1, from 없음)을 저장한다.
     * UNIQUE 충돌(같은 모델 활성 예약 · 쓴 입장권 · 같은 접수 키)은 여기서 삼키지 않고 그대로 올려 보낸다 —
     * 어느 제약인지에 따라 응답이 달라서 접수 유스케이스가 판정한다.
     */
    public Preorder accept(NewPreorder draft, EventActor actor, String reason) {
        PreorderEvent.requireReason(actor, reason);
        Preorder preorder = preorders.saveAndFlush(new Preorder(draft));
        entityManager.persist(new PreorderEvent(preorder.getId(), PreorderEvent.FIRST_SEQUENCE,
                null, PreorderStatus.PENDING_SYNC, actor, reason, clock.instant()));
        return preorder;
    }

    /**
     * from → to 전이. 예약이 지금 from 이 아니면 아무것도 바꾸지 않고 false 를 돌려준다.
     * PENDING_SYNC → PAYABLE 은 외부 등록 번호가 필요하므로 {@link #markPayable} 을 쓴다.
     *
     * @throws IllegalArgumentException 허용하지 않는 전이 — 호출하는 코드의 오류다
     */
    public boolean transition(Long preorderId, PreorderStatus from, PreorderStatus to,
                              EventActor actor, String reason) {
        if (!from.canTransitionTo(to)) {
            throw new IllegalArgumentException("허용하지 않는 전이: " + from + " → " + to);
        }
        if (from == PreorderStatus.PENDING_SYNC && to == PreorderStatus.PAYABLE) {
            throw new IllegalArgumentException("결제 가능 반영은 markPayable 을 쓴다");
        }
        PreorderEvent.requireReason(actor, reason);
        Instant now = clock.instant();
        if (preorders.changeStatus(preorderId, from, to, now) == 0) {
            return false;
        }
        record(preorderId, from, to, actor, reason, now);
        return true;
    }

    /**
     * 외부 등록 확인 → PAYABLE. payable_from 과 외부 예약 번호를 함께 채운다.
     * 이미 반영됐거나 취소 중이면 false — 같은 결과 메시지를 두 번 받아도 한 번만 반영된다.
     */
    public boolean markPayable(Long preorderId, String externalReference) {
        Instant now = clock.instant();
        int updated = preorders.markPayable(preorderId, externalReference, now,
                PreorderStatus.PENDING_SYNC, PreorderStatus.PAYABLE);
        if (updated == 0) {
            return false;
        }
        record(preorderId, PreorderStatus.PENDING_SYNC, PreorderStatus.PAYABLE, EventActor.SYSTEM, null, now);
        return true;
    }

    private void record(Long preorderId, PreorderStatus from, PreorderStatus to,
                        EventActor actor, String reason, Instant now) {
        long sequence = preorders.findEventSequence(preorderId);
        entityManager.persist(new PreorderEvent(preorderId, sequence, from, to, actor, reason, now));
    }
}
