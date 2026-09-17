package com.grandis.nova.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 상품 · 옵션 조회와 관리자 등록·편집. ECS desired 2~6.
 */
@SpringBootApplication(scanBasePackages = "com.grandis.nova")
public class CatalogApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogApplication.class, args);
    }
}
