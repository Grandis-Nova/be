package com.grandis.nova.member.auth.api;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * 리프레시 토큰 쿠키. 원본 AuthCookieSupport 에서 secure 를 설정값으로 뺀 것(03 ⑫). 로컬 http 프로파일만 false.
 * Path 를 재발급·로그아웃 경로로 좁혀 다른 API 요청에는 쿠키가 실리지 않게 한다 — 접수 스파이크 요청에 리프레시가 딸려 갈 이유가 없다.
 * SameSite=Lax: 프론트와 API 가 한 도메인(D-6).
 */
@Component
public class AuthCookies {

    public static final String REFRESH_TOKEN = "refresh_token";
    public static final String PATH = "/api/v1/session";

    @ConfigurationProperties("auth.cookie")
    public record Settings(Boolean secure) {
        public Settings {
            if (secure == null) {
                secure = true;
            }
        }
    }

    private final Settings settings;

    public AuthCookies(Settings settings) {
        this.settings = settings;
    }

    public ResponseCookie refresh(String token, Duration maxAge) {
        return ResponseCookie.from(REFRESH_TOKEN, token)
                .httpOnly(true).secure(settings.secure()).sameSite("Lax").path(PATH).maxAge(maxAge).build();
    }

    public ResponseCookie expiredRefresh() {
        return ResponseCookie.from(REFRESH_TOKEN, "")
                .httpOnly(true).secure(settings.secure()).sameSite("Lax").path(PATH).maxAge(Duration.ZERO).build();
    }
}
