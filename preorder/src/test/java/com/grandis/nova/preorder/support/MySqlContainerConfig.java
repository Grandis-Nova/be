package com.grandis.nova.preorder.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;

/**
 * 운영과 같은 MySQL 8.4 를 띄우고 docs/schema.sql(ERD 의 옮김)로 스키마를 만든다.
 *
 * 스키마는 컨테이너의 초기화 디렉터리로 넣는다 — root 로 실행되므로 shop · external_mock 두 데이터베이스를
 * 만들 수 있다. 앱은 shop 에 붙는다. 컨테이너는 스프링 빈이라 테스트 컨텍스트 캐시와 함께 재사용된다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MySqlContainerConfig {

    static final String IMAGE = "mysql:8.4";

    @Bean
    @ServiceConnection
    MySQLContainer mysql() {
        return new MySQLContainer(IMAGE)
                .withDatabaseName("shop")
                .withCopyFileToContainer(MountableFile.forHostPath(schemaPath()),
                        "/docker-entrypoint-initdb.d/schema.sql");
    }

    private static Path schemaPath() {
        String path = System.getProperty("nova.schema-path");
        if (path == null) {
            throw new IllegalStateException("nova.schema-path 가 없습니다. gradle test 로 실행하세요(preorder/build.gradle).");
        }
        return Path.of(path);
    }
}
