package com.grandis.nova.preorder.cancel;

import com.grandis.nova.preorder.outbox.OutboxMessage.PreorderCancelRequested;
import com.grandis.nova.preorder.outbox.OutboxWriter;
import com.grandis.nova.preorder.preorder.CancelReason;
import com.grandis.nova.preorder.preorder.EventActor;
import com.grandis.nova.preorder.preorder.Preorder;
import com.grandis.nova.preorder.preorder.PreorderLedger;
import com.grandis.nova.preorder.preorder.PreorderTransition;
import com.grandis.nova.preorder.preorder.PreorderTrigger;
import com.grandis.nova.preorder.syncjob.PreorderSyncJobRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 취소 시작 트랜잭션. 사용자 · 관리자 · 만료 · 회차 취소가 모두 이 경로를 탄다.
 *
 * 예약 → CANCELING, 끝나지 않은 REGISTER 작업 무효화, PREORDER_CANCEL_REQUESTED 기록을 한 트랜잭션에서 한다.
 * 잠금 순서는 예약 행 → 그 예약의 작업 행이다(원장이 먼저 예약 행을 잠근다).
 * 주문 정리와 외부 취소는 이 뒤에 이벤트로 이어진다 — 여기서 외부를 부르지 않는다.
 */
@Component
public class CancelStarter {

    private final PreorderLedger ledger;
    private final PreorderSyncJobRepository syncJobs;
    private final OutboxWriter outboxWriter;
    private final Clock clock;

    public CancelStarter(PreorderLedger ledger, PreorderSyncJobRepository syncJobs, OutboxWriter outboxWriter,
                         Clock clock) {
        this.ledger = ledger;
        this.syncJobs = syncJobs;
        this.outboxWriter = outboxWriter;
        this.clock = clock;
    }

    /** 이미 취소 중 · 취소 완료면 아무것도 만들지 않고 지금 상태를 돌려준다. */
    @Transactional
    public PreorderTransition start(Preorder preorder, EventActor actor, String reason, CancelReason cancelReason) {
        PreorderTransition transition = ledger.fire(preorder.getId(), PreorderTrigger.CANCEL_REQUESTED, actor, reason);
        if (transition.applied()) {
            syncJobs.cancelRegister(preorder.getId(), clock.instant());
            outboxWriter.append(new PreorderCancelRequested(preorder.getId(), preorder.getPreorderToken(),
                    preorder.getCustomerId(), cancelReason));
        }
        return transition;
    }
}
