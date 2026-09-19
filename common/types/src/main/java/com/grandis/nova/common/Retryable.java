package com.grandis.nova.common;

/**
 * 이 예외의 오류 응답은 api-spec 봉투의 retryable=true 로 나간다. 같은 요청을 잠시 뒤 다시 보내면 성공할 수 있다는 뜻이다.
 * 예: 폐기 표식 조회가 안 되어 닫은 401. 입력이 틀린 400 이나 권한 없는 403 에는 붙이지 않는다.
 */
public interface Retryable {
}
