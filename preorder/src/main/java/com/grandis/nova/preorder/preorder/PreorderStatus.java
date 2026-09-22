package com.grandis.nova.preorder.preorder;

import java.util.Set;

/**
 * 예약 자신의 생애주기. 결제 여부는 주문 · payments 가 주인이라 여기에 없다 — 결제가 끝나도 PAYABLE 이다.
 * 등록 실패 상태도 없다. 외부 등록이 끝내 실패하면 작업이 DEAD_LETTER 가 되고 관리자가 재처리한다.
 */
public enum PreorderStatus {

    /** 접수됨. 외부 등록 대기. */
    PENDING_SYNC,
    /** 외부 등록 확인. payable_from 부터 24시간 결제 가능. */
    PAYABLE,
    /** 취소 처리 중. 활성으로 남아 재신청을 막는다. */
    CANCELING,
    /** 취소 완료. active_marker 가 NULL 이 되어 재신청할 수 있다. */
    CANCELED;

    /**
     * 허용 전이. CANCELING → PAYABLE 은 주문 쪽이 취소를 거절(배송 시작)했을 때의 되돌림이다 —
     * PAYABLE 에서 시작한 취소에만 해당하는지는 호출하는 쪽이 판정한다.
     */
    public boolean canTransitionTo(PreorderStatus to) {
        return switch (this) {
            case PENDING_SYNC -> Set.of(PAYABLE, CANCELING).contains(to);
            case PAYABLE -> to == CANCELING;
            case CANCELING -> Set.of(CANCELED, PAYABLE).contains(to);
            case CANCELED -> false;
        };
    }
}
