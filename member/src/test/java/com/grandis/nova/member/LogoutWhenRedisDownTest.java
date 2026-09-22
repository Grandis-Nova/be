package com.grandis.nova.member;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.common.security.JwtAuthenticationFilter;
import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.RevocationChecker;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.common.security.TokenType;
import com.grandis.nova.common.web.RequestIdFilter;
import com.grandis.nova.member.auth.api.AuthCookies;
import com.grandis.nova.member.auth.application.RefreshTokenStore;
import com.grandis.nova.member.auth.application.RevocationStore;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * "DELETE /session 은 열린 경로. 표식을 못 심으니 쿠키만 지우고 204". Redis 쪽 저장소와 체커를 모킹해 죽은 상태를 만든다.
 * 셋 다 죽은 경우 외에 한쪽만 실패하는 두 갈래를 따로 둔다 — 그래야 "하나가 실패해도 다른 하나는 시도한다" 가 시험에 잡힌다.
 * 표식을 못 심으면 액세스는 만료까지, 리프레시를 못 지우면 이미 리프레시를 가진 쪽은 14d 까지 — 감수하는 위험 상한.
 */
@SpringBootTest(classes = MemberApplication.class, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/shop?serverTimezone=UTC",
        "spring.datasource.username=nova", "spring.datasource.password=nova-local",
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_READ_COMMITTED",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "jwt.issuer=nova-test",
        "jwt.access-token-validity=1h", "jwt.refresh-token-validity=14d",
        "kakao.client-id=cid", "kakao.client-secret=csecret",
        "kakao.token-uri=https://kauth.kakao.com/oauth/token", "kakao.user-info-uri=https://kapi.kakao.com/v2/user/me",
        "kakao.allowed-redirect-uris=http://localhost:3000/login/kakao/callback",
        "auth.refresh.allowed-origins=http://localhost:3000",
        "admin.username=admin", "admin.password-hash=$2a$12$R9h/cIPz0gi.URNNX3kh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW",
        "auth.cookie.secure=false"
})
@DisplayName("DELETE /session — Redis 가 죽어도 204")
class LogoutWhenRedisDownTest {

    @org.springframework.test.context.DynamicPropertySource
    static void keys(org.springframework.test.context.DynamicPropertyRegistry registry) {
        TestKeys.register(registry);
    }

    @BeforeAll
    static void requireDb() {
        TestInfra.requirePort(3306, "MySQL");
    }

    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy springSecurityFilterChain;
    @Autowired RequestIdFilter requestIdFilter;
    @Autowired JwtTokenProvider provider;
    @MockitoBean RefreshTokenStore refreshTokens;
    @MockitoBean RevocationStore revocations;
    @MockitoBean RevocationChecker checker;   // 필터의 폐기 조회도 죽은 상태로: DELETE /session 은 열린 경로라 통과해야 한다

    private static final DataAccessException DOWN = new RedisConnectionFailureException("down");

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter, springSecurityFilterChain).build();
    }

    private String accessToken() {
        return provider.create("101", Role.USER, UUID.randomUUID(), TokenType.ACCESS);
    }

    @Test
    @DisplayName("저장소·체커가 전부 예외를 던져도 204 이고 refresh 쿠키는 만료로 내려온다")
    void logoutIs204WhenRedisDown() throws Exception {
        doThrow(DOWN).when(refreshTokens).delete(any());
        doThrow(DOWN).when(revocations).revokeSession(any(), any());
        doThrow(new com.grandis.nova.common.security.RevocationCheckFailedException(DOWN)).when(checker).isRevoked(any());

        mvc().perform(delete("/api/v1/session").header(JwtAuthenticationFilter.HEADER, accessToken()))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(AuthCookies.REFRESH_TOKEN, 0));
    }

    @Test
    @DisplayName("리프레시 삭제만 실패: 204 이고 sid 표식은 심어졌다 (액세스는 즉시 죽는다)")
    void markStillWrittenWhenDeleteFails() throws Exception {
        doThrow(DOWN).when(refreshTokens).delete(any());

        mvc().perform(delete("/api/v1/session").header(JwtAuthenticationFilter.HEADER, accessToken()))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(AuthCookies.REFRESH_TOKEN, 0));

        verify(revocations).revokeSession(any(), any());
    }

    @Test
    @DisplayName("표식 쓰기만 실패: 204 이고 리프레시 삭제는 그래도 시도됐다")
    void refreshStillDeletedWhenMarkFails() throws Exception {
        doThrow(DOWN).when(revocations).revokeSession(any(), any());

        mvc().perform(delete("/api/v1/session").header(JwtAuthenticationFilter.HEADER, accessToken()))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(AuthCookies.REFRESH_TOKEN, 0));

        verify(refreshTokens).delete(any());
    }
}
