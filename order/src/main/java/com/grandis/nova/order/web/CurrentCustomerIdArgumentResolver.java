package com.grandis.nova.order.web;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * {@link CurrentCustomerId} 를 채운다. 임시 구현 — common:security(#14)의 리졸버로 바꾼다.
 *
 * common:security 도입 시: 이 파일을 지운다. 같은 이름의 리졸버를 common:security 의 AuthWebConfiguration 이 등록한다.
 */
public class CurrentCustomerIdArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentCustomerId.class)
                && Long.class.equals(parameter.getParameterType());
    }

    @Override
    public Long resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Viewer viewer = CurrentViewerArgumentResolver.current();
        if (viewer.admin()) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return viewer.customerId();
    }
}
