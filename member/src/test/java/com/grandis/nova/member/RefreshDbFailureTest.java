package com.grandis.nova.member;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.common.web.RequestIdFilter;
import com.grandis.nova.member.auth.api.AuthCookies;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.member.auth.application.TokenService;
import com.grandis.nova.member.customer.Customer;
import com.grandis.nova.member.customer.CustomerRepository;
import jakarta.servlet.http.Cookie;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 재발급이 회전 뒤에 DB 를 읽으면, DB 가 죽었을 때 회전만 되고 쿠키를 못 줘 다음 시도가 재사용으로 찍혔다.
 * 지금은 DB 조회가 회전 앞이다 — DB 가 죽은 첫 시도는 500 이지만 저장소 jti 가 그대로라 같은 쿠키로 다시 오면 200 이다.
 * CustomerRepository 를 통째로 모킹한다(JPA 프록시는 spy 로 실메서드를 못 부른다). 리프레시는 TokenService 로 직접 만들고 Redis 는 진짜다.
 */
@SpringBootTest(classes = MemberApplication.class, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/shop?serverTimezone=UTC&characterEncoding=UTF-8",
        "spring.datasource.username=nova", "spring.datasource.password=nova-local",
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_READ_COMMITTED",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.data.redis.host=localhost", "spring.data.redis.port=6379",
        "jwt.issuer=nova-test",
        "jwt.access-token-validity=1h", "jwt.refresh-token-validity=14d",
        "kakao.client-id=cid", "kakao.client-secret=csecret",
        "kakao.token-uri=https://kauth.kakao.com/oauth/token", "kakao.user-info-uri=https://kapi.kakao.com/v2/user/me",
        "kakao.allowed-redirect-uris=http://localhost:3000/login/kakao/callback",
        "auth.refresh.allowed-origins=http://localhost:3000",
        "admin.username=admin", "admin.password-hash=$2a$12$R9h/cIPz0gi.URNNX3kh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW",
        "auth.cookie.secure=false"
})
@DisplayName("재발급 중 DB 장애 — 회전 전에 읽으므로 같은 쿠키로 재시도가 된다")
class RefreshDbFailureTest {

    @org.springframework.test.context.DynamicPropertySource
    static void keys(org.springframework.test.context.DynamicPropertyRegistry registry) {
        TestKeys.register(registry);
    }

    @BeforeAll
    static void requireInfra() {
        TestInfra.requirePort(3306, "MySQL");
        TestInfra.requirePort(6379, "Redis");
    }

    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy springSecurityFilterChain;
    @Autowired RequestIdFilter requestIdFilter;
    @Autowired TokenService tokens;
    @MockitoBean CustomerRepository customers;

    @Test
    @DisplayName("DB 조회가 첫 재발급에서 던지면 500, 두 번째 재발급은 같은 쿠키로 200 (jti 가 회전되지 않았다)")
    void dbFailureBeforeRotationKeepsRefreshUsable() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter, springSecurityFilterChain).build();
        TokenService.IssuedTokens issued = tokens.issue("101", Role.USER);
        Cookie refresh = new Cookie(AuthCookies.REFRESH_TOKEN, issued.refreshToken());
        when(customers.findById(101L))
                .thenThrow(new DataAccessResourceFailureException("db down"))
                .thenReturn(Optional.of(Customer.fromKakao("k", "홍길동")));

        mvc.perform(post("/api/v1/session/refresh").header("Origin", "http://localhost:3000").cookie(refresh))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
        mvc.perform(post("/api/v1/session/refresh").header("Origin", "http://localhost:3000").cookie(refresh))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("홍길동"));
    }
}
