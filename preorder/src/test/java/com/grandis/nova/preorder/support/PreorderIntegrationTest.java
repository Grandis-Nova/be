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
 *
 * 스키마는 flyway-project/migrations 를 Flyway 로 적용해 만든다. 운영 DB 에 쓰는 것과 같은 파일이라
 * 엔티티의 ddl-auto: validate 가 실제 배포 스키마와 대조된다. Flyway 설정은 flyway-project/flyway.toml 과 맞춘다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_READ_COMMITTED",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
        "spring.flyway.locations=filesystem:${nova.migrations-path}",
        "spring.flyway.default-schema=shop",
        "spring.flyway.schemas=shop",
        "spring.flyway.create-schemas=false",
        "spring.flyway.clean-disabled=true",
        "spring.flyway.validate-migration-naming=true",
        "spring.threads.virtual.enabled=true",
        // 릴레이는 테스트가 직접 부른다. 주기 실행이 끼어들면 잠그는 행이 겹쳐 결과가 흔들린다
        "nova.outbox.relay-interval=1h",
        "nova.outbox.transport=log",
        "nova.admission-ticket.secret=" + PreorderIntegrationTest.ADMISSION_TICKET_SECRET
})
@Import(MySqlContainerConfig.class)
public @interface PreorderIntegrationTest {

    /** 테스트 전용 입장권 비밀. 테스트 발급기(AdmissionTickets)가 같은 값으로 서명한다. */
    String ADMISSION_TICKET_SECRET = "nova-test-current-secret-0123456789";
}
