package com.grandis.nova.order.order.domain.exception;

/**
 * 그 예약의 주문이 이미 있다(uq_order_preorder). 예약당 주문은 평생 하나다.
 *
 * 저장소가 제약 이름을 보고 이 예외로 바꾼다 — 호출하는 쪽이 DB 제약 이름 문자열에 기대지 않게.
 * 이 예외가 나면 호출자의 트랜잭션은 rollback-only 다. 기존 주문은 새 트랜잭션에서 조회한다.
 */
public class OrderAlreadyPlacedException extends RuntimeException {

    private final Long preorderId;

    public OrderAlreadyPlacedException(Long preorderId, Throwable cause) {
        super("이 예약의 주문이 이미 있다: preorderId=" + preorderId, cause);
        this.preorderId = preorderId;
    }

    public Long getPreorderId() {
        return preorderId;
    }
}
