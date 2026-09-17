package com.grandis.nova.preorder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 사전예약 접수 · 순번 · 회차 · 배송 차수 · 예약 취소.
 * 대기열을 통과한 트래픽을 Queue Gateway 로부터 받는다. 오픈 직후 폭증하므로 ECS desired 6~12.
 */
@SpringBootApplication(scanBasePackages = "com.grandis.nova")
public class PreorderApplication {

    public static void main(String[] args) {
        SpringApplication.run(PreorderApplication.class, args);
    }
}
