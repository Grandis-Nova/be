package com.grandis.nova.common.security;

import io.jsonwebtoken.Header;
import io.jsonwebtoken.Locator;
import io.jsonwebtoken.ProtectedHeader;
import io.jsonwebtoken.security.Jwk;
import io.jsonwebtoken.security.JwkSet;
import io.jsonwebtoken.security.Jwks;
import java.security.Key;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * 서명 키(있으면)와 검증 공개키 묶음. 토큰 헤더의 `kid` 로 공개키를 고른다(RFC 7515).
 *
 * 공개키 출처는 둘이고 순서대로 본다: (1) 설정 — 개인키에서 계산한 공개키(지금 서명 kid) + `public-keys`(교체 중인 **옛** 키), (2) `jwk-set-uri` 에서 받은 JWKS(캐시).
 * (1) 은 정적이다 — 기동 때 한 번 만든다. (2) 는 **주기 갱신이 없다.** 모르는 kid 가 왔을 때만 한 번 다시 받는다(키 교체 직후 새 kid 를 이렇게 흡수한다).
 * 그래서 키 교체 절차(02 §3 정본)의 "새 공개키 먼저 게시" 는 떠 있는 검증 인스턴스에는 효과가 없고, 서명 전환 뒤 그 인스턴스가 새 kid 를 처음 본 요청에서 따라잡는다.
 *
 * 상한 셋(리뷰 판정으로 못 박음):
 * - 성공한 조회 뒤 10초(MIN_REFRESH_INTERVAL) 안에는 모르는 kid 가 와도 다시 받지 않는다 — 위조 kid 로 발급 서비스에 조회를 퍼붓지 못하게.
 * - 실패한 조회(5xx·타임아웃) 뒤에는 1초(FAILURE_RETRY_AFTER)만 쉬고 다음 요청이 다시 받는다 — 교체 직후 member 가 잠깐 못 받았다고 10초를 401 로 보내지 않게.
 * - 조회는 요청 스레드에서 동기(연결 2s + 읽기 3s). 한 인스턴스에서 그 사이 같은 새 kid 로 온 다른 요청은 기다리지 않고 401 이다(CAS 를 진 쪽).
 *   즉 교체 직후 인스턴스마다 **최대 5초 창** 동안 401 이 날 수 있다. D-2·06 §8 에 적혀 있다.
 * JWKS 서버가 죽어 있으면 캐시된 키로 계속 검증하고, 캐시도 없으면 그 토큰은 거절된다(500 이 아니라 401).
 */
public class JwtKeyRing {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyRing.class);
    static final Duration MIN_REFRESH_INTERVAL = Duration.ofSeconds(10);
    static final Duration FAILURE_RETRY_AFTER = Duration.ofSeconds(1);

    private final String keyId;
    private final RSAPrivateKey signingKey;
    private final Map<String, RSAPublicKey> staticKeys;
    private final String jwkSetUri;
    private final RestClient rest;
    private final Clock clock;
    private final AtomicReference<Map<String, RSAPublicKey>> fetched = new AtomicReference<>(Map.of());
    private final AtomicReference<Instant> lastRefresh = new AtomicReference<>(Instant.EPOCH);

    public JwtKeyRing(JwtProperties properties, Clock clock, RestClient rest) {
        this.clock = clock;
        this.rest = rest;
        this.jwkSetUri = properties.jwkSetUri() == null || properties.jwkSetUri().isBlank() ? null : properties.jwkSetUri();
        Map<String, RSAPublicKey> keys = new LinkedHashMap<>();
        if (properties.issues()) {
            this.keyId = properties.keyId();
            this.signingKey = PemKeys.parsePrivate(properties.privateKey());
            keys.put(keyId, PemKeys.publicOf(signingKey));
        } else {
            this.keyId = null;
            this.signingKey = null;
        }
        properties.publicKeys().forEach((kid, pem) -> keys.putIfAbsent(kid, PemKeys.parsePublic(pem)));
        this.staticKeys = Collections.unmodifiableMap(keys);
    }

    public boolean canSign() {
        return signingKey != null;
    }

    public String keyId() {
        return keyId;
    }

    public RSAPrivateKey signingKey() {
        if (signingKey == null) {
            throw new IllegalStateException("this service has no jwt.private-key — it verifies tokens but does not issue them");
        }
        return signingKey;
    }

    /** JWKS 로 게시할 키: 지금 서명 키의 공개키 + 설정의 공개키(교체 중인 이전 키). 원격에서 받은 키는 다시 게시하지 않는다. */
    public Map<String, RSAPublicKey> publishedKeys() {
        return staticKeys;
    }

    public Optional<RSAPublicKey> resolve(String kid) {
        if (kid == null || kid.isBlank()) {
            return Optional.empty();
        }
        RSAPublicKey key = staticKeys.get(kid);
        if (key != null) {
            return Optional.of(key);
        }
        key = fetched.get().get(kid);
        if (key != null) {
            return Optional.of(key);
        }
        if (jwkSetUri != null && refreshIfAllowed()) {
            key = fetched.get().get(kid);
        }
        return Optional.ofNullable(key);
    }

    /** jjwt 파서에 꽂는 키 선택기. kid 가 없거나 모르는 kid 면 InvalidTokenException — 파서 밖에서 401 로 뭉친다. */
    public Locator<Key> locator() {
        return new Locator<>() {
            @Override
            public Key locate(Header header) {
                String kid = header instanceof ProtectedHeader ph ? ph.getKeyId() : null;
                if (kid == null || kid.isBlank()) {
                    throw new InvalidTokenException("missing kid header");
                }
                return resolve(kid).map(k -> (Key) k).orElseThrow(() -> new InvalidTokenException("unknown kid"));
            }
        };
    }

    private boolean refreshIfAllowed() {
        Instant now = clock.instant();
        Instant last = lastRefresh.get();
        if (Duration.between(last, now).compareTo(MIN_REFRESH_INTERVAL) < 0) {
            return false;
        }
        if (!lastRefresh.compareAndSet(last, now)) {
            return false;   // 다른 스레드가 방금 받았다
        }
        try {
            String json = rest.get().uri(jwkSetUri).retrieve().body(String.class);
            JwkSet set = Jwks.setParser().build().parse(json);
            Map<String, RSAPublicKey> keys = new LinkedHashMap<>();
            for (Jwk<?> jwk : set.getKeys()) {
                if (jwk.getId() != null && usableForSignature(jwk) && jwk.toKey() instanceof RSAPublicKey pub) {
                    keys.put(jwk.getId(), pub);
                }
            }
            fetched.set(Collections.unmodifiableMap(keys));
            log.info("jwks refreshed from {}: {} key(s)", jwkSetUri, keys.size());
            return true;
        } catch (RuntimeException e) {
            // 받기에 실패해도 캐시는 유지한다. 예외 종류만 남긴다(URI 는 설정값이라 실어도 된다).
            // 실패는 10초를 다 쉬지 않는다 — 1초 뒤 다음 요청이 다시 받는다(리뷰 판정 1).
            lastRefresh.set(now.minus(MIN_REFRESH_INTERVAL).plus(FAILURE_RETRY_AFTER));
            log.warn("jwks refresh failed from {}: {}", jwkSetUri, e.getClass().getSimpleName());
            return false;
        }
    }

    /** `use` 가 있으면 sig 여야 하고 `alg` 가 있으면 RS256 이어야 한다. 암호화용·다른 알고리즘용 키를 서명 검증에 쓰지 않는다. */
    private static boolean usableForSignature(Jwk<?> jwk) {
        Object use = jwk.get("use");
        Object alg = jwk.get("alg");
        return (use == null || "sig".equals(use)) && (alg == null || JwtTokenProvider.ALG.equals(alg));
    }

    /** JWKS 문서(RFC 7517). 공개 정보만 들어간다: kty·kid·use·alg·n·e. */
    public Map<String, Object> jwks() {
        var keys = new java.util.ArrayList<Map<String, Object>>();
        publishedKeys().forEach((kid, key) -> {
            Map<String, Object> jwk = new LinkedHashMap<>();
            jwk.put("kty", "RSA");
            jwk.put("kid", kid);
            jwk.put("use", "sig");
            jwk.put("alg", "RS256");
            jwk.put("n", PemKeys.base64UrlUnsigned(key.getModulus()));
            jwk.put("e", PemKeys.base64UrlUnsigned(key.getPublicExponent()));
            keys.add(jwk);
        });
        return Map.of("keys", keys);
    }

    /** JWKS 문서를 정적 공개키로 읽는다(시험·검증 전용 서비스가 파일로 받을 때). */
    public static Map<String, RSAPublicKey> parseJwks(String json) {
        Map<String, RSAPublicKey> keys = new LinkedHashMap<>();
        for (Jwk<?> jwk : Jwks.setParser().build().parse(json).getKeys()) {
            if (jwk.getId() != null && usableForSignature(jwk) && jwk.toKey() instanceof PublicKey pub && pub instanceof RSAPublicKey rsa) {
                keys.put(jwk.getId(), rsa);
            }
        }
        return keys;
    }
}
