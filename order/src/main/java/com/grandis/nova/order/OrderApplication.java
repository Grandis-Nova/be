package com.grandis.nova.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 주문 · 결제기록 · 재고 · 장바구니.
 * 결제 성공 트랜잭션(주문 전이 + payments + 재고 held→sold + 장바구니 차감)이 이 서비스 안에서 닫힌다.
 * ECS desired 2~8.
 */
@SpringBootApplication(scanBasePackages = "com.grandis.nova")
public class OrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
