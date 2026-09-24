package com.grandis.nova.preorder.outbox;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.grandis.nova.preorder.preorder.CancelReason;

/**
 * preorder 가 발행하는 메시지. 계약: contracts/preorder-internal.md 2.2.
 *
 * record 의 칸이 곧 payload 다. 이벤트 종류 · aggregate 를 record 가 함께 정하므로
 * 종류와 본문이 어긋난 메시지를 만들 수 없다. 소비자는 payload 를 믿지 않고 aggregateId 로 원장을 다시 읽는다 —
 * 그래서 payload 에는 식별자와 최소 정보만 담고 민감정보는 넣지 않는다.
 */
public sealed interface OutboxMessage {

    String eventType();

    AggregateType aggregateType();

    Long aggregateId();

    /**
     * 접수 트랜잭션. worker 가 syncJobId 로 작업을 읽어 외부 등록을 보낸다.
     * jobType 은 record 칸이 아니라 고정값이다 — 칸으로 두면 이벤트 종류와 다른 값을 넣을 수 있다.
     */
    record RegisterJobReady(Long syncJobId, String preorderId) implements OutboxMessage {

        @JsonProperty
        public String jobType() {
            return "REGISTER";
        }

        @Override
        public String eventType() {
            return "REGISTER_JOB_READY";
        }

        @Override
        public AggregateType aggregateType() {
            return AggregateType.PREORDER_SYNC_JOB;
        }

        @Override
        public Long aggregateId() {
            return syncJobId;
        }
    }

    /** 주문 정리가 끝난 뒤. worker 가 외부 취소를 보낸다. jobType 은 고정값이다. */
    record CancelJobReady(Long syncJobId, String preorderId) implements OutboxMessage {

        @JsonProperty
        public String jobType() {
            return "CANCEL";
        }

        @Override
        public String eventType() {
            return "CANCEL_JOB_READY";
        }

        @Override
        public AggregateType aggregateType() {
            return AggregateType.PREORDER_SYNC_JOB;
        }

        @Override
        public Long aggregateId() {
            return syncJobId;
        }
    }

    /** 관리자 DLQ 재처리. worker 가 DEAD_LETTER 인 작업만 되돌린다. */
    record SyncJobReprocessRequested(Long syncJobId, String requestedBy) implements OutboxMessage {

        @Override
        public String eventType() {
            return "SYNC_JOB_REPROCESS_REQUESTED";
        }

        @Override
        public AggregateType aggregateType() {
            return AggregateType.PREORDER_SYNC_JOB;
        }

        @Override
        public Long aggregateId() {
            return syncJobId;
        }
    }

    /**
     * 취소 시작 트랜잭션. order 가 주문을 정리하고 PREORDER_ORDER_SETTLED 로 답한다.
     *
     * @param preorderInternalId 원장 조회용 예약 내부 id. 봉투의 aggregateId 로만 나가고 payload 에는 싣지 않는다
     * @param preorderId         공개 UUID(preorder_token)
     * @param cancelSequence     이 취소 시도의 CANCELING 진입 이력 event_sequence. order 가 PREORDER_ORDER_SETTLED 에
     *                           그대로 돌려주고, preorder 는 지금 시도의 결과인지 이것으로 가린다
     */
    record PreorderCancelRequested(@JsonIgnore Long preorderInternalId, String preorderId, Long customerId,
                                   CancelReason reason, Long cancelSequence) implements OutboxMessage {

        @Override
        public String eventType() {
            return "PREORDER_CANCEL_REQUESTED";
        }

        @Override
        public AggregateType aggregateType() {
            return AggregateType.PREORDER;
        }

        @Override
        public Long aggregateId() {
            return preorderInternalId;
        }
    }
}
