package com.grandis.nova.order.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨트롤러 파라미터에 {@link Viewer} 를 넣는다. USER · ADMIN 둘 다 받는 조회에서 쓴다.
 * 회원 것만 다루는 API 는 {@link CurrentCustomerId} 를 쓴다. 임시 자리다 — common:security(#14) 머지 후 바꾼다.
 *
 * common:security 도입 시: common:security 에는 이 애너테이션이 없다(CurrentCustomerId 만 있다). 지우지 말고
 * {@link Viewer} 와 함께 남기거나 common:security 에 추가를 요청한다. 리졸버는 바꿔야 한다({@link CurrentViewerArgumentResolver}).
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentViewer {
}
