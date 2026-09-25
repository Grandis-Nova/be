package com.grandis.nova.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * BaseEntity 의 created_at · updated_at 을 채운다. 안 켜면 NOT NULL 위반으로만 드러난다.
 * 코드가 직접 찍는 시각과 어긋나지 않도록 Auditing 도 같은 {@link Clock} 을 쓴다.
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
     * 마이크로초 아래를 반올림한다. 그대로 두면 저장하고 돌려준 값과 DB 에 들어간 값이 어긋난다 — 같은 행인데
     * 메모리와 DB 의 시각이 다르고, macOS(마이크로초 시계)에서는 드러나지 않는다.
     */
    public static Clock atStorageResolution(Clock base) {
        return Clock.tick(base, STORAGE_RESOLUTION);
    }
}
