package com.grandis.nova.preorder.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨트롤러 파라미터에 로그인 회원의 customers.id 를 넣는다. USER 만 — ADMIN 이면 403 FORBIDDEN.
 *
 * 임시 자리다. 액세스 토큰 검증은 member 팀의 common:security(NV-44)가 맡고, 머지되면 그쪽 같은 이름의 애너테이션으로 바꾼다.
 * 그때까지 인증 주체는 SecurityContext 의 Authentication(이름 = 회원 id, 권한 ROLE_USER · ROLE_ADMIN)이다.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentCustomerId {
}
