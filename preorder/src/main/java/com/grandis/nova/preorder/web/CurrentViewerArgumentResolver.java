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

/** {@link CurrentViewer} 를 채운다. 임시 구현 — NV-44 의 인증 필터가 들어오면 그쪽 주체로 바꾼다. */
public class CurrentViewerArgumentResolver implements HandlerMethodArgumentResolver {

    static final String ROLE_ADMIN = "ROLE_ADMIN";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentViewer.class)
                && Viewer.class.equals(parameter.getParameterType());
    }

    @Override
    public Viewer resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        return current();
    }

    /** SecurityContext 의 인증 주체. 이름이 회원 id 이고, ADMIN 은 회원 id 가 없다. */
    static Viewer current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken
                || !authentication.isAuthenticated()) {
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        }
        boolean admin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ROLE_ADMIN::equals);
        if (admin) {
            return new Viewer(null, true);
        }
        try {
            return new Viewer(Long.valueOf(authentication.getName()), false);
        } catch (NumberFormatException e) {
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        }
    }
}
