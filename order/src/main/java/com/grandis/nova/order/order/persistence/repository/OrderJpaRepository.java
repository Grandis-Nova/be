package com.grandis.nova.order.order.persistence.repository;

import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.persistence.entity.OrderJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * 벌크 UPDATE 는 영속성 컨텍스트를 거치지 않으므로 앞뒤로 flush · clear 한다. 그러지 않으면
 * 같은 트랜잭션에서 이미 읽어 둔 엔티티가 옛 상태를 들고 있다.
 */
public interface OrderJpaRepository extends JpaRepository<OrderJpaEntity, Long> {

    Optional<OrderJpaEntity> findByOrderToken(String orderToken);

    Optional<OrderJpaEntity> findByPreorderId(Long preorderId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update OrderJpaEntity o
               set o.status = :to, o.eventSequence = o.eventSequence + 1, o.updatedAt = :now
             where o.id = :id and o.status = :from
            """)
    int changeStatus(@Param("id") Long id, @Param("from") OrderStatus from, @Param("to") OrderStatus to,
                     @Param("now") Instant now);

    @Query(value = "SELECT status FROM orders WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<OrderStatus> findStatusForUpdate(@Param("id") Long id);

    @Query("select o.eventSequence from OrderJpaEntity o where o.id = :id")
    long findEventSequence(@Param("id") Long id);
}
