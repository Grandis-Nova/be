package com.grandis.nova.common.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * D-2: 폐기 조회가 실패했을 때 401 로 닫는 경로 목록. 목록 밖은 전부 열린다(통과 + 경고 + 카운터).
 * 기본값은 08 D-2 의 다섯 개다. 설정으로 덮어쓸 수 있지만, 빈 목록으로 두면 재발급까지 열려 폐기 세션이 새 토큰을 받으므로 기본값을 쓴다.
 * 패턴은 Spring Security 7 의 PathPattern 문법이다. 메서드는 보지 않는다 — 같은 경로의 GET 도 닫힌다(조회가 닫혀도 손해가 없는 경로들).
 */
@ConfigurationProperties("auth.revocation-check")
public record RevocationCheckProperties(List<String> failClosedPaths) {

    public static final List<String> DEFAULT_FAIL_CLOSED_PATHS = List.of(
            "/api/v1/admin/**",                        // 관리자 기능. POST /api/v1/admin/session 은 공개라 토큰이 없어 여기 안 걸린다
            "/api/v1/session/refresh",                 // 폐기 세션이 새 토큰을 받는 유일한 통로
            "/api/v1/orders/*/payment-attempts/**",    // 돈이 움직인다
            "/api/v1/reservations/*/cancel",           // 되돌릴 수 없다
            "/api/v1/orders/*/cancel");

    public RevocationCheckProperties {
        if (failClosedPaths == null) {
            failClosedPaths = DEFAULT_FAIL_CLOSED_PATHS;
        }
    }
}
