package com.grandis.nova.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * SQS 소비 — 외부 예약 등록 · 재시도 · 알림 기록.
 *
 * 큐 적체(Backlog per Task)로 1~20 까지 스케일된다. FARGATE_SPOT 을 쓴다 —
 * 중단돼도 메시지는 큐에 남는다. 여러 개가 동시에 떠도 되는 일만 둔다.
 * 스케줄 작업은 여기 두지 않는다 (batch 로).
 */
@SpringBootApplication(scanBasePackages = "com.grandis.nova")
public class WorkerApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(WorkerApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }
}
