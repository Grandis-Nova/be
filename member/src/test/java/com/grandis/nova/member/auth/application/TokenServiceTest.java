package com.grandis.nova.member.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grandis.nova.common.security.InvalidTokenException;
import com.grandis.nova.common.security.JwtProperties;
import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.RevocationCheckFailedException;
import com.grandis.nova.common.security.RevocationChecker;
import com.grandis.nova.common.security.RevocationLookupUnavailableException;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.common.security.TokenClaims;
import com.grandis.nova.common.security.TokenType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import static org.mockito.Mockito.doThrow;

/**
 * 07 §1 단계 5: 발급 쌍의 sid 동일 · 회전 시 만료 유지 · 재사용 → 세션 폐기 · revokeAll → nbf. 저장소는 모킹, 토큰은 진짜로 만든다.
 */
@DisplayName("TokenService")
class TokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-19T16:00:00Z");
    private static final Duration ACCESS = Duration.ofHours(1);
    private static final Duration REFRESH = Duration.ofDays(14);

    private final MutableClock clock = new MutableClock(NOW);
    private final JwtProperties properties = new JwtProperties("nova-test", "test-secret-key-for-jwt-provider-32bytes", ACCESS, REFRESH);
    private final JwtTokenProvider provider = new JwtTokenProvider(properties, clock);
    private RefreshTokenStore refreshTokens;
    private RevocationStore revocations;
    private RevocationChecker checker;
    private TokenService service;

    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void set(Instant i) { now = i; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @BeforeEach
    void setUp() {
        refreshTokens = mock(RefreshTokenStore.class);
        revocations = mock(RevocationStore.class);
        checker = mock(RevocationChecker.class);
        when(checker.isRevoked(any())).thenReturn(false);
        service = new TokenService(provider, properties, refreshTokens, revocations, checker, clock);
    }

    @Test
    @DisplayName("issue: 액세스·리프레시가 같은 sid 를 갖고, 리프레시 jti 가 14일 TTL 로 저장된다")
    void issuePairSharesSessionAndStoresRefresh() {
        TokenService.IssuedTokens tokens = service.issue("101", Role.USER);

        TokenClaims access = provider.parse(tokens.accessToken());
        TokenClaims refresh = provider.parse(tokens.refreshToken());
        assertThat(access.type()).isEqualTo(TokenType.ACCESS);
        assertThat(refresh.type()).isEqualTo(TokenType.REFRESH);
        assertThat(access.sessionId()).isEqualTo(refresh.sessionId());
        assertThat(access.tokenId()).isNotEqualTo(refresh.tokenId());
        assertThat(tokens.refreshTokenMaxAge()).isEqualTo(REFRESH);
        verify(refreshTokens).save(refresh.sessionId(), refresh.tokenId(), REFRESH);
    }

    @Test
    @DisplayName("rotate: 3일 뒤 회전하면 새 리프레시의 만료는 원래 그대로(14일에서 안 늘어남), 쿠키 Max-Age 는 남은 11일, 저장소는 옛 jti→새 jti 로")
    void rotateKeepsAbsoluteExpiry() {
        TokenService.IssuedTokens first = service.issue("101", Role.USER);
        TokenClaims oldRefresh = provider.parse(first.refreshToken());
        when(refreshTokens.rotate(eq(oldRefresh.sessionId()), eq(oldRefresh.tokenId()), any(), any())).thenReturn(true);
        clock.set(NOW.plus(Duration.ofDays(3)));

        TokenService.IssuedTokens rotated = service.rotate(first.refreshToken());

        TokenClaims newRefresh = provider.parse(rotated.refreshToken());
        TokenClaims newAccess = provider.parse(rotated.accessToken());
        assertThat(newRefresh.expiresAt()).isEqualTo(oldRefresh.expiresAt());
        assertThat(newRefresh.sessionId()).isEqualTo(oldRefresh.sessionId());
        assertThat(newRefresh.tokenId()).isNotEqualTo(oldRefresh.tokenId());
        assertThat(newAccess.issuedAt()).isEqualTo(NOW.plus(Duration.ofDays(3)));
        assertThat(rotated.refreshTokenMaxAge()).isEqualTo(Duration.ofDays(11));
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(refreshTokens).rotate(eq(oldRefresh.sessionId()), eq(oldRefresh.tokenId()), eq(newRefresh.tokenId()), ttl.capture());
        assertThat(ttl.getValue()).isEqualTo(Duration.ofDays(11));
    }

    @Test
    @DisplayName("rotate: 저장소가 false(재사용) 를 돌려주면 리프레시 삭제 + sid 폐기 + 401")
    void reuseRevokesSession() {
        TokenService.IssuedTokens first = service.issue("101", Role.USER);
        TokenClaims refresh = provider.parse(first.refreshToken());
        when(refreshTokens.rotate(any(), any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.rotate(first.refreshToken())).isInstanceOf(InvalidTokenException.class);

        verify(refreshTokens).delete(refresh.sessionId());
        verify(revocations).revokeSession(refresh.sessionId(), ACCESS);
    }

    @Test
    @DisplayName("rotate: 액세스 토큰을 내면 401 이고 저장소를 건드리지 않는다")
    void rotateRejectsAccessToken() {
        TokenService.IssuedTokens first = service.issue("101", Role.USER);

        assertThatThrownBy(() -> service.rotate(first.accessToken())).isInstanceOf(InvalidTokenException.class);
        verify(refreshTokens, never()).rotate(any(), any(), any(), any());
    }

    @Test
    @DisplayName("rotate: 제재(nbf)·로그아웃된 세션의 리프레시는 401 — 재발급이 제재 우회 통로가 되지 않는다")
    void rotateRejectsRevokedSession() {
        TokenService.IssuedTokens first = service.issue("101", Role.USER);
        when(checker.isRevoked(any())).thenReturn(true);

        assertThatThrownBy(() -> service.rotate(first.refreshToken())).isInstanceOf(InvalidTokenException.class);
        verify(refreshTokens, never()).rotate(any(), any(), any(), any());
    }

    @Test
    @DisplayName("rotate: 폐기 조회가 안 되면 닫는다(D-2 재발급) — retryable 401, 회전 안 함")
    void rotateClosesWhenLookupUnavailable() {
        TokenService.IssuedTokens first = service.issue("101", Role.USER);
        when(checker.isRevoked(any())).thenThrow(new RevocationCheckFailedException(new RuntimeException("down")));

        assertThatThrownBy(() -> service.rotate(first.refreshToken())).isInstanceOf(RevocationLookupUnavailableException.class);
        verify(refreshTokens, never()).rotate(any(), any(), any(), any());
    }

    @Test
    @DisplayName("rotate: 만료된 리프레시는 401 (parse 에서 걸린다)")
    void rotateRejectsExpiredRefresh() {
        TokenService.IssuedTokens first = service.issue("101", Role.USER);
        clock.set(NOW.plus(REFRESH));

        assertThatThrownBy(() -> service.rotate(first.refreshToken())).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    @DisplayName("revoke(로그아웃): 리프레시 삭제 + sid 표식(TTL=액세스 만료)")
    void revokeDeletesRefreshAndMarksSession() {
        TokenService.IssuedTokens first = service.issue("101", Role.USER);
        TokenClaims access = provider.parse(first.accessToken());

        service.revoke(access);

        InOrder order = Mockito.inOrder(revocations, refreshTokens);
        order.verify(revocations).revokeSession(access.sessionId(), ACCESS);   // 필터가 읽는 표식이 먼저
        order.verify(refreshTokens).delete(access.sessionId());
    }

    @Test
    @DisplayName("revoke: 표식 쓰기가 실패해도 리프레시 삭제는 시도하고, Redis 예외는 그대로 올라간다")
    void revokeStillDeletesRefreshWhenMarkFails() {
        TokenService.IssuedTokens first = service.issue("101", Role.USER);
        TokenClaims access = provider.parse(first.accessToken());
        doThrow(new RedisConnectionFailureException("down")).when(revocations).revokeSession(any(), any());

        assertThatThrownBy(() -> service.revoke(access)).isInstanceOf(DataAccessException.class);
        verify(refreshTokens).delete(access.sessionId());
    }

    @Test
    @DisplayName("revoke: 리프레시 삭제가 실패해도 표식은 이미 심어져 있고, Redis 예외는 그대로 올라간다")
    void revokeStillMarksWhenDeleteFails() {
        TokenService.IssuedTokens first = service.issue("101", Role.USER);
        TokenClaims access = provider.parse(first.accessToken());
        doThrow(new RedisConnectionFailureException("down")).when(refreshTokens).delete(any());

        assertThatThrownBy(() -> service.revoke(access)).isInstanceOf(DataAccessException.class);
        verify(revocations).revokeSession(access.sessionId(), ACCESS);
    }

    @Test
    @DisplayName("revoke: Redis 예외가 아닌 것은 삼키지 않고 즉시 전파한다 (프로그래밍 오류가 204 뒤에 숨지 않게)")
    void revokePropagatesNonDataAccessErrors() {
        TokenService.IssuedTokens first = service.issue("101", Role.USER);
        TokenClaims access = provider.parse(first.accessToken());
        doThrow(new IllegalStateException("bug")).when(revocations).revokeSession(any(), any());

        assertThatThrownBy(() -> service.revoke(access)).isInstanceOf(IllegalStateException.class);
        verify(refreshTokens, never()).delete(any());
    }

    @Test
    @DisplayName("revokeAll(제재): 회원 nbf 를 리프레시 만료 TTL 로 심는다")
    void revokeAllMarksNotBefore() {
        service.revokeAll("101");

        verify(revocations).revokeAll("101", REFRESH);
    }
}
