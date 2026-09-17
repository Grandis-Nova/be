package com.grandis.nova.common;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * 생성·변경 시각을 공통으로 갖는 엔티티의 부모.
 *
 * ERD 가 시간을 UTC datetime(6) 으로 정했으므로 Instant 를 쓴다.
 * LocalDateTime 은 시간대 정보가 없어 "이 값이 UTC 인가" 를 코드에서 알 수 없다.
 *
 * 쓰는 앱은 @EnableJpaAuditing 을 켜야 한다. 안 켜면 값이 null 로 들어가고
 * NOT NULL 제약에 걸려서야 드러난다.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
