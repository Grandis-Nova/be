package com.grandis.nova.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.Clock;
import java.util.Optional;

/**
 * BaseEntity 의 created_at · updated_at 을 채운다. 안 켜면 NOT NULL 위반으로만 드러난다.
 * 코드가 직접 찍는 시각과 어긋나지 않도록 Auditing 도 같은 {@link Clock} 을 쓴다.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(clock.instant());
    }
}
