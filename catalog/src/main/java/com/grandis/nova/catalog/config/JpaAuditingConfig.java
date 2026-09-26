package com.grandis.nova.catalog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * common:types 의 BaseEntity 가 created_at · updated_at 을 채우려면 Auditing 이 켜져 있어야 한다.
 * 안 켜면 값이 null 로 들어가고 NOT NULL 제약에 걸려서야 드러난다.
 *
 * Auditing 시각도 {@link Clock} 에서 가져온다. 등록 단계 완료 시각 · 리스 만료처럼 코드가 직접 찍는 시각과
 * 같은 시계를 써야 한 트랜잭션 안의 시각들이 서로 어긋나지 않는다.
 *
 * common:security 도입 시: 그쪽 JwtConfiguration 이 조건 없이 같은 이름의 clock() 빈을 정의해 기동이 실패한다(order 선례).
 * 이 시계(마이크로초로 내린 {@link #atStorageResolution})는 유지해야 한다 — common 의 시계를 이것으로 바꾸거나
 * 여기 빈 이름을 바꾸고 @Primary 를 단다.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {

    /** DB 시각 칼럼(datetime(6))이 담는 가장 작은 단위. */
    static final Duration STORAGE_RESOLUTION = Duration.ofNanos(1_000);

    @Bean
    Clock clock() {
        return atStorageResolution(Clock.systemUTC());
    }

    @Bean
    DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(clock.instant());
    }

    /**
     * 시각을 마이크로초로 내린다. JVM 시계는 OS 에 따라 나노초까지 주는데(Linux) MySQL 은 datetime(6) 에 넣으며
     * 마이크로초 아래를 반올림한다(실측: …00.999999600Z → 00:00:01.000000). 그대로 두면 저장하고 돌려준 값과 DB 에 들어간 값이
     * 어긋난다 — 같은 행인데 메모리와 DB 의 시각이 다르고, macOS(마이크로초 시계)에서는 드러나지 않는다.
     */
    public static Clock atStorageResolution(Clock base) {
        return Clock.tick(base, STORAGE_RESOLUTION);
    }
}
