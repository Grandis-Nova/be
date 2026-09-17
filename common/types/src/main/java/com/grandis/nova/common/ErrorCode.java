package com.grandis.nova.common;

/**
 * 업무 오류 코드의 계약.
 *
 * 모듈마다 자기 enum 을 만들어 이걸 구현한다. 도메인 코드를 common 에 모으면
 * order 의 코드를 고치려고 common 을 건드리게 되고, 그 변경이 전 모듈을 다시 빌드시킨다.
 *
 * HTTP 상태를 int 로 들고 있다. HttpStatus 를 쓰면 이 모듈이 spring-web 을 의존하게 되고,
 * 그러면 worker · batch 까지 web 을 끌어온다.
 *
 * defaultMessage 는 사용자에게 보여 줄 문구다. 내부 오류 원문을 여기 담지 않는다
 * (FR-U-04: 내부 오류 원문·민감정보 비노출).
 */
public interface ErrorCode {

    /** 응답의 code 필드. enum 이 자동으로 만족한다. */
    String name();

    int status();

    String defaultMessage();
}
