package com.grandis.nova.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * JWT 를 만들고 파싱한다. 발급 정책(sid 생성·회전·폐기)은 member 의 TokenService 가 갖고, 여기는 서명과 클레임만 안다.
 *
 * 클레임은 06 §4 의 일곱 개뿐이다: sub · sid · jti · role · type · iat · exp.
 * 파싱은 서명·issuer·만료·필수 클레임 일곱 개를 전부 검사하고, 하나라도 어긋나면 InvalidTokenException 하나로 뭉친다.
 * jjwt 가 던지는 것(서명·형식·타입 변환)도, 이 클래스의 필수 클레임 검사도 전부 그 예외로 나간다. 호출자는 이유를 구분하지 않는다.
 *
 * 시각은 주입된 Clock 에서만 온다. jjwt 의 만료 검사에도 같은 시계를 넣는다.
 * 만료 경계는 이 클래스가 정한다: exp 와 같은 순간이면 만료다(exp 는 "이때부터 무효"). jjwt 의 판정에 기대지 않고 직접 비교한다.
 * iat·exp 는 JWT 규격상 epoch 초라 밀리초 아래가 잘린다. TokenClaims 의 시각은 초 정밀도로 다룬다.
 */
@Component
public class JwtTokenProvider {

    static final String CLAIM_SESSION_ID = "sid";
    static final String CLAIM_ROLE = "role";
    static final String CLAIM_TYPE = "type";

    private final String issuer;
    private final SecretKey secretKey;
    private final JwtProperties properties;
    private final Clock clock;

    public JwtTokenProvider(JwtProperties properties, Clock clock) {
        this.issuer = properties.issuer();
        // 키 길이가 알고리즘을 정한다(≥32 HS256 · ≥48 HS384 · ≥64 HS512). 운영 키(base64 64바이트)는 HS512.
        this.secretKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.properties = properties;
        this.clock = clock;
    }

    /** 타입에 맞는 만료(액세스 1h · 리프레시 14d, D-7)로 새 토큰을 만든다. jti 는 매번 새로 난다. */
    public String create(String subject, Role role, UUID sessionId, TokenType type) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(type == TokenType.ACCESS
                ? properties.accessTokenValidity()
                : properties.refreshTokenValidity());
        return create(subject, role, sessionId, type, now, expiresAt);
    }

    /**
     * 만료 시각을 지정해 만든다. 리프레시 회전용이다. 회전은 원 토큰의 만료를 그대로 넘겨 부르므로 절대 만료가 늘지 않는 것은
     * 호출자(TokenService)가 지킨다. 이 클래스는 지정한 만료가 이미 지났는지만 본다 — 만료된 세션은 다시 로그인해야 한다.
     */
    public String create(String subject, Role role, UUID sessionId, TokenType type, Instant expiresAt) {
        Instant now = clock.instant();
        if (!expiresAt.isAfter(now)) {
            throw new InvalidTokenException("expiresAt is not after now");
        }
        return create(subject, role, sessionId, type, now, expiresAt);
    }

    private String create(String subject, Role role, UUID sessionId, TokenType type, Instant now, Instant expiresAt) {
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(issuer)
                .subject(subject)
                .claim(CLAIM_SESSION_ID, sessionId.toString())
                .claim(CLAIM_ROLE, role.name())
                .claim(CLAIM_TYPE, type.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 서명·issuer·형식을 jjwt 가 검사하고, 필수 클레임 일곱 개와 만료 경계는 여기서 검사한다.
     * 타입(ACCESS/REFRESH)을 가려 쓰는 것은 호출자 몫이다. 필터는 ACCESS 만, 재발급은 REFRESH 만 받는다.
     */
    public TokenClaims parse(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidTokenException("token is blank");
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .requireIssuer(issuer)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            // 비표준 클레임은 jjwt 의 타입 변환(get(name, String.class) → RequiredTypeException)에 기대지 않고
            // Object 로 꺼내 직접 문자열화한다. 그래야 실패 이유가 "malformed claim sid" 처럼 클레임 이름으로 남는다.
            // 실측: JwtTokenProviderTest.sessionIdNotAString. 클레임 추출 전체를 try 안에 두는 것도 같은 이유다.
            String subject = requireText(claims.getSubject(), "sub");
            UUID sessionId = requireUuid(stringClaim(claims, CLAIM_SESSION_ID), CLAIM_SESSION_ID);
            UUID tokenId = requireUuid(claims.getId(), "jti");
            Role role = requireEnum(stringClaim(claims, CLAIM_ROLE), Role.class, CLAIM_ROLE);
            TokenType type = requireEnum(stringClaim(claims, CLAIM_TYPE), TokenType.class, CLAIM_TYPE);
            Instant issuedAt = requireInstant(claims.getIssuedAt(), "iat");
            Instant expiresAt = requireInstant(claims.getExpiration(), "exp");

            // jjwt 도 만료를 보지만 경계(같은 순간)의 판정을 이 클래스가 소유하기 위해 직접 비교한다.
            if (!expiresAt.isAfter(clock.instant())) {
                throw new InvalidTokenException("expired");
            }
            return new TokenClaims(subject, sessionId, tokenId, role, type, issuedAt, expiresAt);
        } catch (InvalidTokenException e) {
            throw e;
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException("jwt rejected (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static String stringClaim(Claims claims, String name) {
        Object value = claims.get(name);
        return value == null ? null : String.valueOf(value);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new InvalidTokenException("missing claim " + name);
        }
        return value;
    }

    private static UUID requireUuid(String value, String name) {
        try {
            return UUID.fromString(requireText(value, name));
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException("malformed claim " + name);
        }
    }

    private static <E extends Enum<E>> E requireEnum(String value, Class<E> type, String name) {
        try {
            return Enum.valueOf(type, requireText(value, name));
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException("unknown claim value " + name);
        }
    }

    private static Instant requireInstant(Date value, String name) {
        if (value == null) {
            throw new InvalidTokenException("missing claim " + name);
        }
        return value.toInstant();
    }
}
