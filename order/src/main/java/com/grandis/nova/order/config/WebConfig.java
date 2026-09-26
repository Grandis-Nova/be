package com.grandis.nova.order.config;

import com.grandis.nova.order.web.CurrentCustomerIdArgumentResolver;
import com.grandis.nova.order.web.CurrentViewerArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 임시 인증 리졸버 등록. common:security(#14) 머지 후 그쪽 리졸버로 바꾼다.
 *
 * common:security 도입 시: CurrentCustomerIdArgumentResolver 등록 줄을 지운다. common:security 의 AuthWebConfiguration 이
 * 같은 애너테이션의 리졸버를 등록한다. CurrentViewer 를 order 에 남기면 그 등록만 남기고, 아니면 이 클래스를 지운다.
 */
@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentCustomerIdArgumentResolver());
        resolvers.add(new CurrentViewerArgumentResolver());
    }
}
