package com.grandis.nova.preorder.event;

/**
 * order 가 예약 취소에 따른 주문 정리를 끝냈다(계약 2.3). result 는 order 의 판정이라 payload 를 믿는다(I-3).
 *
 * @param reason REJECTED 일 때 SHIPPED · PAID(만료 경합)
 */
public record PreorderOrderSettled(String preorderId, Result result, String reason) {

    /** 주문 정리 결과. 미결제 주문 취소와 결제 주문 환불 완료는 둘 다 CANCELED 로 온다. */
    public enum Result {
        NO_ORDER,
        CANCELED,
        REJECTED
    }
}
