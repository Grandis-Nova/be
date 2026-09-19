package com.grandis.nova.common.security;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시각은 전부 이 Clock 빈에서 온다. 발급 iat·exp, 파싱의 만료 판정, nbf 비교 모두.
 * 테스트가 다른 시계를 끼워 만료 경계를 태울 수 있어야 하므로 Instant.now() 를 직접 부르지 않는다.
 *
 * 실측(JwtConfigurationTest): 이 클래스는 자동설정이 아니라 컴포넌트 스캔되는 일반 설정이라
 * @ConditionalOnMissingBean 이 동작하지 않는다 — 앱이 Clock 을 또 정의하면 빈이 둘이 됐다.
 * 그래서 조건 없이 하나만 두고, 앱이나 테스트가 바꿔 끼울 때는 @Primary 로 덮는다. @Primary 없이 하나 더 두면 빈 둘만으로는 조용히 뜨고,
 * Clock 을 주입받는 JwtTokenProvider 가 있는 실제 앱에서는 기동이 실패한다(실측: JwtConfigurationTest.appDefinedClockWithoutPrimary,
 * "expected single matching bean but found 2").
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
