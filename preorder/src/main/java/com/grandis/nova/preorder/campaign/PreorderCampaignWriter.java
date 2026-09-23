package com.grandis.nova.preorder.campaign;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.preorder.PreorderErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 회차 · 차수를 바꾸는 트랜잭션. 모두 회차 행을 잠근 채 한다 —
 * 접수도 같은 행을 잠그므로 "오픈 전인가" 판정과 접수가 경합하지 않는다.
 *
 * 외부 호출(catalog)은 여기 없다. 상품 확인은 트랜잭션 밖에서 끝내고 들어온다.
 */
@Component
class PreorderCampaignWriter {

    private final PreorderCampaignRepository campaigns;
    private final ShipmentBatchRepository batches;
    private final Clock clock;

    PreorderCampaignWriter(PreorderCampaignRepository campaigns, ShipmentBatchRepository batches, Clock clock) {
        this.campaigns = campaigns;
        this.batches = batches;
        this.clock = clock;
    }

    /** 없으면 만들고 있으면 바꾼다. 오픈 뒤에는 바꾸지 않는다. */
    @Transactional
    PreorderCampaign upsert(Long productId, Instant opensAt, Instant closesAt) {
        return campaigns.findForUpdate(productId)
                .map(campaign -> reschedule(campaign, opensAt, closesAt))
                .orElseGet(() -> campaigns.save(PreorderCampaign.of(productId, opensAt, closesAt)));
    }

    /** 오픈 전 전체 교체. 순번이 나간 뒤에 구간을 바꾸면 그 순번의 배송 차수가 달라진다. */
    @Transactional
    List<ShipmentBatch> replaceBatches(Long productId, ShipmentBatchPlan plan) {
        PreorderCampaign campaign = campaigns.findForUpdate(productId)
                .orElseThrow(() -> new BusinessException(PreorderErrorCode.PRODUCT_NOT_FOUND));
        requireBeforeOpen(campaign);
        batches.deleteByProductId(productId);
        batches.flush();
        return batches.saveAll(plan.toBatches(productId));
    }

    private PreorderCampaign reschedule(PreorderCampaign campaign, Instant opensAt, Instant closesAt) {
        requireBeforeOpen(campaign);
        campaign.reschedule(opensAt, closesAt);
        return campaign;
    }

    private void requireBeforeOpen(PreorderCampaign campaign) {
        if (campaign.isOpened(clock.instant())) {
            throw new BusinessException(PreorderErrorCode.PRODUCT_ALREADY_OPEN);
        }
    }
}
