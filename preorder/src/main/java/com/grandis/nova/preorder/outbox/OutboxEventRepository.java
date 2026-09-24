package com.grandis.nova.preorder.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /** 발행 완료 표시. 이미 채워졌으면 바꾸지 않는다. */
    @Transactional
    @Modifying
    @Query("update OutboxEvent e set e.publishedAt = :now where e.id = :id and e.publishedAt is null")
    int markPublished(@Param("id") Long id, @Param("now") Instant now);

    /** 발행 실패 횟수를 올린다. */
    @Transactional
    @Modifying
    @Query("update OutboxEvent e set e.publishAttempts = e.publishAttempts + 1 where e.id = :id")
    int recordFailure(@Param("id") Long id);

    /** 릴레이가 보낼 행을 잠가 가져온다. 다른 인스턴스가 잠근 행은 건너뛴다(SKIP LOCKED). */
    @Query(value = """
            SELECT * FROM outbox_events
             WHERE published_at IS NULL AND created_at <= :createdBefore AND event_type IN (:eventTypes)
             ORDER BY id
             LIMIT :limit
               FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockUnpublished(@Param("createdBefore") Instant createdBefore,
                                      @Param("eventTypes") Collection<String> eventTypes, @Param("limit") int limit);
}
