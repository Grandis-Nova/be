package com.grandis.nova.preorder.campaign;

import com.grandis.nova.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 사전예약 회차. 사전예약 상품당 1행이고 상품 id 가 곧 PK 다.
 *
 * 접수는 이 행을 잠근다({@link PreorderCampaignRepository#findForUpdate}).
 * 순번 카운터가 모집 일정과 같은 행에 있어서 접수 트랜잭션이 한 번만 잠그면 되고,
 * 순번의 유일성은 이 잠금에서 나온다. 상품 정보 수정은 products 를 바꾸므로 접수와 서로 막지 않는다.
 */
@Entity
@Table(name = "preorder_campaigns")
public class PreorderCampaign extends BaseEntity {

    @Id
    private Long productId;

    @Column(nullable = false)
    private Instant opensAt;

    @Column(nullable = false)
    private Instant closesAt;

    @Column(nullable = false)
    private long nextQueuePosition;

    private Instant openNotifiedAt;

    protected PreorderCampaign() {
    }

    /** 오픈 시각 이상, 마감 시각 미만일 때 접수를 받는다. */
    public boolean isAccepting(Instant now) {
        return !now.isBefore(opensAt) && now.isBefore(closesAt);
    }

    /** 오픈 뒤에는 일정 · 배송 차수를 바꿀 수 없다. */
    public boolean isOpened(Instant now) {
        return !now.isBefore(opensAt);
    }

    /**
     * 다음 순번을 발급하고 카운터를 올린다. 반드시 {@link PreorderCampaignRepository#findForUpdate} 로
     * 잠근 행에서 부른다 — 잠그지 않은 행에서 부르면 두 트랜잭션이 같은 번호를 낸다.
     */
    public long issueQueuePosition() {
        long position = nextQueuePosition;
        nextQueuePosition = position + 1;
        return position;
    }

    public Long getProductId() {
        return productId;
    }

    public Instant getOpensAt() {
        return opensAt;
    }

    public Instant getClosesAt() {
        return closesAt;
    }

    public long getNextQueuePosition() {
        return nextQueuePosition;
    }

    public Instant getOpenNotifiedAt() {
        return openNotifiedAt;
    }
}
