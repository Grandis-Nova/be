package com.grandis.nova.preorder.preorder;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 접수 트랜잭션이 정한 새 예약의 내용. 순번 · 차수는 회차를 잠근 뒤 정해진 값이어야 한다.
 *
 * @param admissionTicketId 소비한 입장권의 SHA-256(16진수 64자). 관리자 대신 접수면 null
 */
public record NewPreorder(
        String preorderToken,
        Long customerId,
        Long productId,
        Long optionId,
        Long shipmentBatchId,
        long queuePosition,
        String admissionTicketId,
        String idempotencyKey,
        String productTitleSnapshot,
        String optionTitleSnapshot,
        BigDecimal unitPriceSnapshot
) {

    public NewPreorder {
        Objects.requireNonNull(preorderToken, "preorderToken");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(optionId, "optionId");
        Objects.requireNonNull(shipmentBatchId, "shipmentBatchId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(productTitleSnapshot, "productTitleSnapshot");
        Objects.requireNonNull(optionTitleSnapshot, "optionTitleSnapshot");
        Objects.requireNonNull(unitPriceSnapshot, "unitPriceSnapshot");
        if (queuePosition <= 0) {
            throw new IllegalArgumentException("queuePosition 은 1 이상이어야 한다: " + queuePosition);
        }
    }
}
