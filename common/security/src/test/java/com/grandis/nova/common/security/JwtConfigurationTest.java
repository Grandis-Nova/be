package com.grandis.nova.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration; // Boot 4: spring-boot-validation 모듈로 이동
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 설정 바인딩과 Clock 빈. 07 §2 실측 표의 "@ConditionalOnMissingBean 이 일반 @Configuration 에서 동작하는가" 를 여기서 잰다.
 * 스프링 부트는 그 조건을 자동설정 클래스에서만 쓰라고 하므로, 앱이 Clock 을 따로 정의했을 때 빈이 하나인지 둘인지 실측한다.
 */
@DisplayName("JwtConfiguration · JwtProperties 바인딩")
class JwtConfigurationTest {

    private static final String SECRET_64 = "nova-test-signing-key-not-a-secret-xxxxxxxxxxxxxxxxxxxxxxxxxxxxx";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(JwtConfiguration.class);

    private ApplicationContextRunner valid() {
        return runner.withPropertyValues(
                "jwt.issuer=nova",
                "jwt.secret=" + SECRET_64,
                "jwt.access-token-validity=1h",
                "jwt.refresh-token-validity=14d");
    }

    @Test
    @DisplayName("yml 의 1h·14d 가 Duration 으로 바인딩되고 Clock 빈이 생긴다")
    void bindsDurationsAndProvidesClock() {
        valid().run(ctx -> {
            JwtProperties p = ctx.getBean(JwtProperties.class);
            assertThat(p.issuer()).isEqualTo("nova");
            assertThat(p.accessTokenValidity()).isEqualTo(Duration.ofHours(1));
            assertThat(p.refreshTokenValidity()).isEqualTo(Duration.ofDays(14));
            assertThat(ctx).hasSingleBean(Clock.class);
        });
    }

    // 실패 "이유" 까지 단언한다. hasFailed() 만 보면 다른 이유로 실패해도 통과한다(리뷰 메모 ㄱ).
    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        StringBuilder sb = new StringBuilder();
        while (cur != null) {
            sb.append(cur.getMessage()).append(" | ");
            cur = cur.getCause();
        }
        return sb.toString();
    }

    @Test
    @DisplayName("secret 이 32자 미만이면 기동이 실패한다 (예시 파일의 CHANGE_ME 가 여기서 걸린다)")
    void shortSecretFailsStartup() {
        valid().withPropertyValues("jwt.secret=CHANGE_ME").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootMessage(ctx.getStartupFailure())).contains("secret");
        });
    }

    @Test
    @DisplayName("32자가 넘어도 CHANGE_ME 로 시작하는 자리표시자면 기동이 실패한다 (예시 파일을 그대로 복사한 경우)")
    void placeholderSecretFailsStartupEvenIfLong() {
        valid().withPropertyValues("jwt.secret=CHANGE_ME_TO_A_BASE64_STRING_OF_AT_LEAST_32_CHARS").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootMessage(ctx.getStartupFailure())).contains("jwt.secret is still the example placeholder");
        });
    }

    @Test
    @DisplayName("issuer 가 없으면 기동이 실패한다")
    void missingIssuerFailsStartup() {
        runner.withPropertyValues(
                "jwt.secret=" + SECRET_64,
                "jwt.access-token-validity=1h",
                "jwt.refresh-token-validity=14d").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootMessage(ctx.getStartupFailure())).contains("issuer");
        });
    }

    @Test
    @DisplayName("access-token-validity 가 0 이나 음수면 기동이 실패한다 — 통과하면 발급 즉시 만료 토큰이 나간다")
    void nonPositiveValidityFailsStartup() {
        valid().withPropertyValues("jwt.access-token-validity=0s").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootMessage(ctx.getStartupFailure())).contains("jwt.access-token-validity must be positive");
        });
        valid().withPropertyValues("jwt.refresh-token-validity=-1h").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootMessage(ctx.getStartupFailure())).contains("jwt.refresh-token-validity must be positive");
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class AppClockConfig {
        @Bean
        @Primary
        Clock appClock() {
            return Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Test
    @DisplayName("앱이 @Primary 로 Clock 을 정의하면 그것이 주입된다 (실측: @ConditionalOnMissingBean 은 일반 설정에서 빈이 둘이 됐다)")
    void appDefinedPrimaryClockWins() {
        valid().withUserConfiguration(AppClockConfig.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBeansOfType(Clock.class)).hasSize(2);
            assertThat(ctx.getBean(Clock.class).instant()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class AppClockWithoutPrimaryConfig {
        @Bean
        Clock appClock() {
            return Clock.systemUTC();
        }
    }

    @Test
    @DisplayName("실측: @Primary 없이 Clock 을 또 정의하면 — 빈 둘만으로는 기동이 성공하고, Clock 을 주입받는 빈(JwtTokenProvider)이 있어야 기동이 실패한다")
    void appDefinedClockWithoutPrimary() {
        // 주입받는 빈이 없으면 조용히 뜬다. 타입으로 꺼낼 때야 둘 중 못 고른다.
        valid().withUserConfiguration(AppClockWithoutPrimaryConfig.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBeansOfType(Clock.class)).hasSize(2);
            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> ctx.getBean(Clock.class)))
                    .isInstanceOf(org.springframework.beans.factory.NoUniqueBeanDefinitionException.class);
        });
        // 실제 앱에는 JwtTokenProvider 가 Clock 을 받으므로 기동에서 걸린다.
        valid().withUserConfiguration(AppClockWithoutPrimaryConfig.class, JwtTokenProvider.class).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootMessage(ctx.getStartupFailure()))
                    .contains("java.time.Clock")
                    .contains("expected single matching bean but found 2");
        });
    }

    @Test
    @DisplayName("toString 은 서명 키를 찍지 않는다")
    void toStringMasksSecret() {
        JwtProperties p = new JwtProperties("nova", SECRET_64, Duration.ofHours(1), Duration.ofDays(14));
        assertThat(p.toString()).doesNotContain(SECRET_64).contains("****");
    }
}
