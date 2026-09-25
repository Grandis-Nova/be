package com.grandis.nova.order.order.domain.repository;

import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderItem;
import com.grandis.nova.order.order.vo.OrderToken;

import java.util.List;
import java.util.Optional;

/**
 * 주문 읽기 포트. 조회 · 판정 유스케이스는 이것만 주입받는다 — 쓰기({@link OrderWriter})를 함께 쥐면
 * 원장을 거치지 않은 전이를 만들 수 있다.
 *
 * 잠그지 않고 읽는다. 읽은 상태로 전이 여부를 정하지 않는다 — 그 판정은 원장의 fire 가 잠근 채로 한다.
 */
public interface OrderReader {

    Optional<Order> findById(Long orderId);

    /** 공개 토큰으로 찾는다. 조회 API 는 이 값만 받는다. */
    Optional<Order> findByOrderToken(OrderToken orderToken);

    /** 예약의 주문. 예약당 주문은 평생 하나라(uq_order_preorder) 0~1건이다. */
    Optional<Order> findByPreorderId(Long preorderId);

    List<OrderItem> findItems(Long orderId);
}
