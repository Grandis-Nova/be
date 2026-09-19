package com.grandis.nova.member.auth.infrastructure.kakao;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * D-1(프론트 주도 코드 교환): 프론트가 카카오에서 받은 code 를 우리에게 넘기고, 우리가 카카오 토큰 엔드포인트에 code 를 낸다.
 * client secret 은 여기(서버)에만 있다. redirect_uri 는 카카오가 토큰 교환 때 인가 요청과 같은 값인지 대조하므로 프론트가 보낸 값을
 * 그대로 쓰되, allowed-redirect-uris 목록 밖이면 거절한다 — 아무 URI 나 받으면 code 가 다른 사이트로 새는 통로가 된다.
 * 카카오 응답이 늦으면 접수와 무관한 로그인 스레드만 잠기지만, 그래도 상한을 둔다.
 */
@Validated
@ConfigurationProperties("kakao")
public record KakaoProperties(
        @NotBlank String clientId,
        @NotBlank String clientSecret,
        @NotBlank String tokenUri,
        @NotBlank String userInfoUri,
        @NotEmpty List<String> allowedRedirectUris,
        Duration connectTimeout,
        Duration readTimeout
) {
    public KakaoProperties {
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(2);
        }
        if (readTimeout == null) {
            readTimeout = Duration.ofSeconds(5);
        }
    }
}
