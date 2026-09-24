package com.grandis.nova.preorder.event;

import com.grandis.nova.preorder.cancel.CancelRequestPayload;
import com.grandis.nova.preorder.outbox.OutboxMessage.CancelJobReady;
import com.grandis.nova.preorder.outbox.OutboxWriter;
import com.grandis.nova.preorder.preorder.EventActor;
import com.grandis.nova.preorder.preorder.Preorder;
import com.grandis.nova.preorder.preorder.PreorderEvent;
import com.grandis.nova.preorder.preorder.PreorderEventRepository;
import com.grandis.nova.preorder.preorder.PreorderLedger;
import com.grandis.nova.preorder.preorder.PreorderRepository;
import com.grandis.nova.preorder.preorder.PreorderStatus;
import com.grandis.nova.preorder.preorder.PreorderTrigger;
import com.grandis.nova.preorder.syncjob.PreorderSyncJob;
import com.grandis.nova.preorder.syncjob.PreorderSyncJobRepository;
import com.grandis.nova.preorder.syncjob.SyncJobStatus;
import com.grandis.nova.preorder.syncjob.SyncJobType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Optional;

/**
 * preorder 가 받는 이벤트의 처리. 메시지 하나 = 트랜잭션 하나다.
 *
 * 같은 메시지를 두 번 받아도 결과가 같아야 한다(SQS 는 at-least-once). 상태 머신이 지금 상태에서 의미 없는 사건을
 * 무시하고, 작업은 예약 행을 잠근 채 있는지 먼저 본다. 순서도 보장되지 않으므로 판단은 원장으로 한다.
 */
@Component
public class PreorderEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PreorderEventHandler.class);

    private static final String REJECTED_BY_ORDER = "ORDER_REJECTED";

    /** 취소를 시작한 주체 → Mock 감사 사유. 만료는 SYSTEM 이 시작한다. */
    private static final Map<EventActor, String> MOCK_CANCEL_REASONS = Map.of(
            EventActor.USER, "USER_CANCEL",
            EventActor.ADMIN, "ADMIN_CANCEL",
            EventActor.SYSTEM, "DEADLINE_EXCEEDED");

    private final PreorderRepository preorders;
    private final PreorderEventRepository events;
    private final PreorderSyncJobRepository syncJobs;
    private final PreorderLedger ledger;
    private final OutboxWriter outboxWriter;
    private final JsonMapper jsonMapper;

    public PreorderEventHandler(PreorderRepository preorders, PreorderEventRepository events,
                                PreorderSyncJobRepository syncJobs, PreorderLedger ledger, OutboxWriter outboxWriter,
                                JsonMapper jsonMapper) {
        this.preorders = preorders;
        this.events = events;
        this.syncJobs = syncJobs;
        this.ledger = ledger;
        this.outboxWriter = outboxWriter;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 외부 등록 · 취소 성공. 작업 원장이 SUCCEEDED 인지 다시 본 뒤에만 반영한다.
     * 등록 성공이 취소 중인 예약에 늦게 오면 상태 머신이 무시한다 — 외부 쪽은 CANCEL 작업이 정리한다.
     */
    @Transactional
    public void onExternalJobSucceeded(ExternalJobSucceeded message) {
        Optional<PreorderSyncJob> job = syncJobs.findById(message.syncJobId())
                .filter(found -> found.getStatus() == SyncJobStatus.SUCCEEDED);
        if (job.isEmpty()) {
            log.warn("성공하지 않은 작업의 성공 이벤트를 무시한다 syncJobId={}", message.syncJobId());
            return;
        }
        Long preorderId = job.get().getPreorderId();
        if (job.get().getJobType() == SyncJobType.REGISTER) {
            ledger.confirmRegister(preorderId, message.externalNumber());
        } else {
            ledger.fire(preorderId, PreorderTrigger.CANCEL_COMPLETED, EventActor.SYSTEM, null);
        }
    }

    /**
     * 주문 정리 결과. 주문이 없거나 취소됐으면 외부 취소 작업을 만들고, 거절이면 PAYABLE 로 되돌린다.
     * 외부 취소는 주문 정리가 끝난 뒤에만 한다 — 주문이 거절되면(배송 시작) 외부 취소를 되돌릴 수 없다.
     */
    @Transactional
    public void onOrderSettled(PreorderOrderSettled message) {
        Preorder preorder = preorders.findByPreorderToken(message.preorderId())
                .orElseThrow(() -> new IllegalArgumentException("예약이 없다: " + message.preorderId()));
        switch (message.result()) {
            case NO_ORDER, CANCELED -> requestExternalCancel(preorder);
            case REJECTED -> ledger.fire(preorder.getId(), PreorderTrigger.CANCEL_REJECTED,
                    EventActor.SYSTEM, rejectionReason(message.reason()));
        }
    }

    /** 예약 행을 잠근 채 취소 중인지, CANCEL 작업이 이미 있는지 본다 — 같은 결과를 두 번 받아도 작업은 하나다. */
    private void requestExternalCancel(Preorder preorder) {
        PreorderStatus status = preorders.findStatusForUpdate(preorder.getId()).orElseThrow();
        if (status != PreorderStatus.CANCELING
                || syncJobs.findByPreorderIdAndJobType(preorder.getId(), SyncJobType.CANCEL).isPresent()) {
            return;
        }
        String payload = jsonMapper.writeValueAsString(new CancelRequestPayload(preorder.getPreorderToken(),
                preorder.getExternalReference(), mockCancelReason(preorder.getId())));
        PreorderSyncJob job = syncJobs.save(PreorderSyncJob.cancel(preorder.getId(), payload));
        outboxWriter.append(new CancelJobReady(job.getId(), preorder.getPreorderToken()));
    }

    /** 이력에 남길 거절 사유. order 가 사유를 주지 않았으면 결과만 남긴다. */
    private static String rejectionReason(String orderReason) {
        return orderReason == null ? REJECTED_BY_ORDER : REJECTED_BY_ORDER + ":" + orderReason;
    }

    /** 취소를 시작한 이력의 주체로 Mock 감사 사유를 정한다. */
    private String mockCancelReason(Long preorderId) {
        return events.findFirstByPreorderIdAndToStatusOrderByEventSequenceDesc(preorderId, PreorderStatus.CANCELING)
                .map(PreorderEvent::getActor)
                .map(MOCK_CANCEL_REASONS::get)
                .orElse(null);
    }
}
