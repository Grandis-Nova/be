package com.grandis.nova.preorder.preorder;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/**
 * 상태를 바꾸는 쿼리는 모두 "현재 상태를 조건으로 한 UPDATE" 이고 영향 행 수를 돌려준다.
 * 0 이면 이미 다른 요청이 바꾼 것이다. 이력은 이 인터페이스가 아니라 {@link PreorderLedger} 가 함께 남긴다 —
 * 서비스가 이 메서드를 직접 부르지 않는다.
 *
 * 벌크 UPDATE 는 영속성 컨텍스트를 거치지 않으므로 앞뒤로 flush · clear 한다. 그러지 않으면
 * 같은 트랜잭션에서 이미 읽어 둔 엔티티가 옛 상태를 들고 있다.
 */
public interface PreorderRepository extends JpaRepository<Preorder, Long> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Preorder p
               set p.status = :to, p.eventSequence = p.eventSequence + 1, p.updatedAt = :now
             where p.id = :id and p.status = :from
            """)
    int changeStatus(@Param("id") Long id, @Param("from") PreorderStatus from, @Param("to") PreorderStatus to,
                     @Param("now") Instant now);

    /**
     * 외부 등록 확인 → 결제 가능. payable_from 은 처음 한 번만 찍는다(여기서 24시간이 결제 기한).
     * PENDING_SYNC 가 아니면(이미 반영됐거나 취소 중) 0 을 돌려주고 아무것도 바꾸지 않는다 —
     * 늦게 도착한 등록 성공이 취소된 예약을 되살리지 않는다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Preorder p
               set p.status = :payable,
                   p.payableFrom = coalesce(p.payableFrom, :now),
                   p.externalReference = :externalReference,
                   p.eventSequence = p.eventSequence + 1,
                   p.updatedAt = :now
             where p.id = :id and p.status = :pendingSync
            """)
    int markPayable(@Param("id") Long id, @Param("externalReference") String externalReference,
                    @Param("now") Instant now,
                    @Param("pendingSync") PreorderStatus pendingSync, @Param("payable") PreorderStatus payable);

    /** 방금 올린 이력 번호. 같은 트랜잭션의 UPDATE 가 행을 잠그고 있으므로 다른 트랜잭션이 끼어들 수 없다. */
    @Query("select p.eventSequence from Preorder p where p.id = :id")
    long findEventSequence(@Param("id") Long id);
}
