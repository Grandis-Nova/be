package com.grandis.nova.preorder.web;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** {@link CurrentCustomerId} 를 채운다. 임시 구현 — NV-44 의 리졸버로 바꾼다. */
public class CurrentCustomerIdArgumentResolver implements HandlerMethodArgumentResolver {

    static final String ROLE_ADMIN = "ROLE_ADMIN";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentCustomerId.class)
                && Long.class.equals(parameter.getParameterType());
    }

    @Override
    public Long resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken
                || !authentication.isAuthenticated()) {
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        }
        boolean admin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ROLE_ADMIN::equals);
        if (admin) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        try {
            return Long.valueOf(authentication.getName());
        } catch (NumberFormatException e) {
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        }
    }
}
