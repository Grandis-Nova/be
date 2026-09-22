package com.grandis.nova.preorder.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * common:types 의 BaseEntity 가 created_at · updated_at 을 채우려면 Auditing 이 켜져 있어야 한다.
 * 안 켜면 값이 null 로 들어가고 NOT NULL 제약에 걸려서야 드러난다.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing
public class JpaAuditingConfig {
}
