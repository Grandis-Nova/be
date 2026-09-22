package com.grandis.nova.preorder.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 운영과 같은 MySQL 8.4 를 띄운다. 빈 shop 데이터베이스만 만들고, 테이블은 컨텍스트가 뜰 때
 * Flyway 가 마이그레이션으로 만든다({@link PreorderIntegrationTest}).
 * 컨테이너는 스프링 빈이라 테스트 컨텍스트 캐시와 함께 재사용된다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MySqlContainerConfig {

    static final String IMAGE = "mysql:8.4";

    @Bean
    @ServiceConnection
    MySQLContainer mysql() {
        return new MySQLContainer(IMAGE).withDatabaseName("shop");
    }
}
