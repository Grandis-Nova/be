package com.grandis.nova.member.auth.application;

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
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 원본 JwtTokenLifecycle 의 축약(04). 발급·회전·폐기·전체 폐기 넷. replaceAccessToken(닉네임 변경용)은 없다.
 *
 * 회전(RTR)은 세 검사를 지나야 한다.
 * 1. REFRESH 타입의 유효한 토큰인가(서명·만료).
 * 2. 폐기되지 않았는가 — 재발급 경로는 api-spec 에서 공개(쿠키로 식별)라 필터의 폐기 조회를 안 거친다. 여기서 직접 본다.
 *    안 보면 제재(nbf)된 회원이 리프레시로 새 토큰을 받아 제재를 우회한다. 조회가 안 되면 닫는다(D-2: 재발급은 닫는 경로) → retryable 401.
 * 3. 저장된 jti 와 같은가 — 다르면 이미 회전된 리프레시를 다시 낸 것(탈취 의심). 그 세션을 통째로 끊는다.
 * 회전해도 절대 만료는 늘지 않는다. 새 리프레시는 원 토큰의 exp 를 그대로 받는다.
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    private final JwtTokenProvider provider;
    private final JwtProperties properties;
    private final RefreshTokenStore refreshTokens;
    private final RevocationStore revocations;
    private final RevocationChecker revocationChecker;
    private final Clock clock;

    public TokenService(JwtTokenProvider provider, JwtProperties properties, RefreshTokenStore refreshTokens,
                        RevocationStore revocations, RevocationChecker revocationChecker, Clock clock) {
        this.provider = provider;
        this.properties = properties;
        this.refreshTokens = refreshTokens;
        this.revocations = revocations;
        this.revocationChecker = revocationChecker;
        this.clock = clock;
    }

    /** 로그인. 새 sid 하나에 액세스·리프레시 한 쌍. 리프레시 jti 를 저장해 두어야 회전이 된다. */
    public IssuedTokens issue(String subject, Role role) {
        UUID sessionId = UUID.randomUUID();
        String access = provider.create(subject, role, sessionId, TokenType.ACCESS);
        String refresh = provider.create(subject, role, sessionId, TokenType.REFRESH);
        TokenClaims refreshClaims = provider.parse(refresh);
        refreshTokens.save(sessionId, refreshClaims.tokenId(), properties.refreshTokenValidity());
        return new IssuedTokens(access, refresh, properties.refreshTokenValidity());
    }

    /**
     * 재발급. 실패는 전부 401 이다. 재사용 탐지면 세션을 끊고 401, 폐기 조회 불가면 retryable 401.
     * 폐기 검사는 **두 번**이다(05 ①): 회전 전 한 번, 회전 후 원 클레임으로 한 번 더. 첫 검사와 발급 사이에 revokeAll(nbf) 이 끼면
     * 새 토큰의 iat 가 nbf 뒤라 검사를 빠져나가는데, 원 리프레시의 iat 는 nbf 앞이라 두 번째 검사가 잡는다. 잡히면 세션을 끊는다.
     */
    public IssuedTokens rotate(String refreshToken) {
        TokenClaims claims = provider.parse(refreshToken);
        if (claims.type() != TokenType.REFRESH) {
            throw new InvalidTokenException("not a refresh token: " + claims.type());
        }
        boolean revoked;
        try {
            revoked = revocationChecker.isRevoked(claims);
        } catch (RevocationCheckFailedException e) {
            log.warn("refresh rotation closed: revocation lookup failed sid={} cause={}", shortSid(claims), e.getMessage());
            throw new RevocationLookupUnavailableException();
        }
        if (revoked) {
            throw new InvalidTokenException("refresh of revoked session sid=" + shortSid(claims));
        }

        // parse 가 먼저 만료(exp ≤ now)를 떨구므로 여기 도달하는 remaining 은 항상 양수다. 0 이하 방어는 여기 없다 — 있다면 parse 가 느슨해진 것이다.
        Instant now = clock.instant();
        Duration remaining = Duration.between(now, claims.expiresAt());
        String newRefresh = provider.create(claims.subject(), claims.role(), claims.sessionId(), TokenType.REFRESH, claims.expiresAt());
        UUID newJti = provider.parse(newRefresh).tokenId();
        boolean rotated = refreshTokens.rotate(claims.sessionId(), claims.tokenId(), newJti, remaining);
        if (!rotated) {
            // 저장된 jti 가 다르거나 키가 없다 = 이미 회전된 리프레시의 재사용. 원 소유자와 탈취자 중 누가 냈든 세션을 끊는다(RFC 9700).
            // 표식·삭제를 각각 시도하고(05 ③) Redis 예외는 여기서 삼킨다 — 응답은 어차피 401 이고, 못 끊은 사실은 WARN 으로 남는다.
            revokeSessionBestEffort(claims.sessionId(), "refresh reuse");
            log.warn("refresh reuse detected, session revoked sid={}", shortSid(claims));
            throw new InvalidTokenException("refresh reuse sid=" + shortSid(claims));
        }

        // 두 번째 검사(05 ①). 실패(조회 불가)도 폐기로 본다 — 재발급은 닫는 경로이고, 새 jti 를 클라이언트가 못 받은 채 끝나면 그 세션은 어차피 죽는다.
        boolean revokedMeanwhile;
        try {
            revokedMeanwhile = revocationChecker.isRevoked(claims);
        } catch (RevocationCheckFailedException e) {
            log.warn("post-rotation revocation lookup failed sid={} cause={}", shortSid(claims), e.getMessage());
            revokedMeanwhile = true;
        }
        if (revokedMeanwhile) {
            revokeSessionBestEffort(claims.sessionId(), "revoked during rotation");
            throw new InvalidTokenException("session revoked during rotation sid=" + shortSid(claims));
        }
        String newAccess = provider.create(claims.subject(), claims.role(), claims.sessionId(), TokenType.ACCESS);
        return new IssuedTokens(newAccess, newRefresh, remaining);
    }

    /** 표식 먼저, 삭제 다음, 각각 시도. Redis 예외는 WARN 으로만 — 호출자가 어차피 401 을 낼 자리에서 쓴다. */
    private void revokeSessionBestEffort(UUID sessionId, String why) {
        try {
            revoke(sessionId);
        } catch (DataAccessException e) {
            log.warn("{}: session revocation incomplete sid={} cause={}", why, sessionId.toString().substring(0, 8), e.getClass().getSimpleName());
        }
    }

    /**
     * 로그아웃. sid 폐기 표식을 먼저 심고(필터가 읽는 것은 이 표식이라 액세스 토큰이 즉시 죽는다), 그다음 리프레시를 지운다.
     * 둘은 각각 시도한다 — 하나가 실패해도 다른 하나는 한다. Redis 예외(DataAccessException)만 모아 마지막에 던진다.
     * 프로그래밍 오류는 삼키지 않는다. 표식을 못 심으면 액세스는 만료(1h)까지, 리프레시를 못 지우면 이미 리프레시를 가진 쪽은
     * 리프레시 만료(14d)까지 재발급할 수 있다 — 단 응답이 쿠키를 지우므로 본인 브라우저는 리프레시를 잃는다(D-2 "감수하는 위험 상한").
     */
    public void revoke(TokenClaims accessClaims) {
        revoke(accessClaims.sessionId());
    }

    /** sid 만으로 끊는다. 로그아웃은 액세스·리프레시 어느 쪽 토큰에서든 sid 를 얻는다(05 ⑤). */
    public void revoke(UUID sessionId) {
        String sid = sessionId.toString().substring(0, 8);
        DataAccessException failure = null;
        try {
            revocations.revokeSession(sessionId, properties.accessTokenValidity());
        } catch (DataAccessException e) {
            log.warn("logout: revocation mark not written sid={} cause={}", sid, e.getClass().getSimpleName());
            failure = e;
        }
        try {
            refreshTokens.delete(sessionId);
        } catch (DataAccessException e) {
            log.warn("logout: refresh token not deleted sid={} cause={}", sid, e.getClass().getSimpleName());
            failure = failure == null ? e : failure;
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** 제재·탈퇴. 회원의 모든 토큰을 끊는다. 지금 이전(같은 초 포함)에 발급된 것은 전부 무효. */
    public void revokeAll(String subject) {
        revocations.revokeAll(subject, properties.refreshTokenValidity());
    }

    private static String shortSid(TokenClaims claims) {
        return claims.sessionId().toString().substring(0, 8);
    }

    /** 발급 결과. refreshTokenMaxAge 는 쿠키 Max-Age 다 — 회전 때는 남은 만료라 14일보다 짧다. */
    public record IssuedTokens(String accessToken, String refreshToken, Duration refreshTokenMaxAge) {
    }
}
