package com.grandis.nova.preorder.syncjob;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.outbox.OutboxMessage.SyncJobReprocessRequested;
import com.grandis.nova.preorder.outbox.OutboxWriter;
import com.grandis.nova.preorder.preorder.PreorderRepository;
import com.grandis.nova.preorder.preorder.PreorderStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * DEAD_LETTER 인 REGISTER 작업의 재처리 요청을 남긴다. 작업을 되돌리는 것은 worker 다(조건부라 여러 번 와도 한 번).
 * 취소 중 · 취소된 예약은 되살리지 않는다. 확인 뒤 취소가 끼어들어도 worker 의 조건부 되돌림이 0행이 된다.
 */
@Component
public class SyncJobReprocessor {

    private final PreorderSyncJobRepository syncJobs;
    private final PreorderRepository preorders;
    private final OutboxWriter outboxWriter;

    public SyncJobReprocessor(PreorderSyncJobRepository syncJobs, PreorderRepository preorders,
                              OutboxWriter outboxWriter) {
        this.syncJobs = syncJobs;
        this.preorders = preorders;
        this.outboxWriter = outboxWriter;
    }

    /** @return 요청 시점의 작업 */
    @Transactional
    public PreorderSyncJob reprocess(Long syncJobId, String requestedBy) {
        PreorderSyncJob job = syncJobs.findById(syncJobId)
                .orElseThrow(() -> new BusinessException(PreorderErrorCode.SYNC_JOB_NOT_FOUND));
        PreorderStatus preorderStatus = preorders.findById(job.getPreorderId()).orElseThrow().getStatus();
        Optional<String> blocker = blocker(job.getJobType(), job.getStatus(), preorderStatus);
        if (blocker.isPresent()) {
            throw new BusinessException(PreorderErrorCode.SYNC_JOB_NOT_REPROCESSABLE,
                    Map.of("reason", blocker.get()));
        }
        outboxWriter.append(new SyncJobReprocessRequested(job.getId(), requestedBy));
        return job;
    }

    /** 재처리할 수 없는 까닭. 할 수 있으면 비어 있다. */
    static Optional<String> blocker(SyncJobType jobType, SyncJobStatus status, PreorderStatus preorderStatus) {
        if (jobType != SyncJobType.REGISTER || status != SyncJobStatus.DEAD_LETTER) {
            return Optional.of("jobType=%s, status=%s".formatted(jobType, status));
        }
        if (preorderStatus == PreorderStatus.CANCELING || preorderStatus == PreorderStatus.CANCELED) {
            return Optional.of("preorderStatus=" + preorderStatus);
        }
        return Optional.empty();
    }
}
