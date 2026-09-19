package com.grandis.nova.common.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * JWT 발급·검증 설정. 서명 키와 만료 시간은 발급(member)과 검증(HTTP 서비스 4개)이 같은 값을 써야 한다.
 *
 * secret 은 32바이트 이상. HMAC 알고리즘은 키 길이가 정한다(≥32 HS256 · ≥48 HS384 · ≥64 HS512).
 * 예시 파일이 권하는 `openssl rand -base64 64` 는 88자라 운영은 HS512 로 돈다. 테스트가 그 경로도 태운다.
 * 짧으면 기동 때 여기서 걸린다 — 첫 요청에서 서명 실패로 드러나는 것보다 낫다. 예시 파일의 자리표시자(`CHANGE_ME…`)도 여기서 걸린다 —
 * 길이만 보면 레포에 적힌 문자열이 서명 키가 된 채로 뜬다.
 *
 * 만료는 D-7: 액세스 1h · 리프레시 14d. 0 이나 음수는 바인딩을 통과하면 "발급은 되는데 즉시 만료" 가 되어 로그인만 100% 실패하고
 * 부팅 로그에 단서가 없으므로 생성자에서 막는다.
 */
@Validated
@ConfigurationProperties("jwt")
public record JwtProperties(
        @NotBlank String issuer,
        @NotBlank @Size(min = 32) String secret,
        @NotNull Duration accessTokenValidity,
        @NotNull Duration refreshTokenValidity
) {

    public JwtProperties {
        if (secret != null && secret.startsWith("CHANGE_ME")) {
            throw new IllegalArgumentException("jwt.secret is still the example placeholder; generate one with `openssl rand -base64 64`");
        }
        requirePositive(accessTokenValidity, "jwt.access-token-validity");
        requirePositive(refreshTokenValidity, "jwt.refresh-token-validity");
    }

    private static void requirePositive(Duration value, String name) {
        if (value != null && (value.isZero() || value.isNegative())) {
            throw new IllegalArgumentException(name + " must be positive, was " + value);
        }
    }

    /** 서명 키가 로그·오류 메시지에 평문으로 찍히지 않게 한다. */
    @Override
    public String toString() {
        return "JwtProperties[issuer=" + issuer + ", secret=****(" + (secret == null ? 0 : secret.length())
                + " chars), accessTokenValidity=" + accessTokenValidity
                + ", refreshTokenValidity=" + refreshTokenValidity + "]";
    }
}
