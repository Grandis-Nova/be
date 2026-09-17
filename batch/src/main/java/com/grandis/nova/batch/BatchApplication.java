package com.grandis.nova.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 만료 스캔 · 정합성 검사.
 *
 * ECS desired 1 로 고정한다. 여러 개가 뜨면 같은 스캔이 중복 실행되고,
 * 정합성 검사는 중복 실행 자체가 요구사항 위반이다(FR-C-10).
 */
@SpringBootApplication(scanBasePackages = "com.grandis.nova")
@EnableScheduling
public class BatchApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(BatchApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }
}
