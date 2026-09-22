package com.grandis.nova.preorder.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * MySQL 8.4 위에서 도는 통합 테스트. DB 동작을 검증하는 테스트는 이것을 붙인다.
 *
 * 운영 설정(application.yml)은 저장소에 없으므로(*.yml 은 커밋하지 않음) 테스트가 기대는 값을 여기서 준다.
 * 특히 격리 수준 — 빠뜨리면 MySQL 기본값 REPEATABLE READ 로 돌아 운영과 다른 잠금 동작을 검증하게 된다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_READ_COMMITTED",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.jdbc.time_zone=UTC"
})
@Import(MySqlContainerConfig.class)
public @interface PreorderIntegrationTest {
}
