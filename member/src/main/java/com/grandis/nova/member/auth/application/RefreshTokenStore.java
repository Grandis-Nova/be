package com.grandis.nova.member.auth.application;

import java.time.Duration;
import java.util.UUID;

/**
 * 세션(sid)마다 "현재 유효한 리프레시 jti" 하나를 든다. 원본 RefreshTokenStore 에서 회원별 ZSet 관련 두 메서드를 뺀 것(04).
 * revokeAll 은 sid 를 열거하지 않고 회원 not-before 로 한다(RevocationStore).
 */
public interface RefreshTokenStore {

    /** 로그인 때. 세션의 현재 리프레시 jti 를 TTL 과 함께 저장한다. */
    void save(UUID sessionId, UUID refreshTokenId, Duration ttl);

    /**
     * 회전(RTR). 저장된 jti 가 expected 와 같을 때만 새 jti 로 바꾼다 — 비교와 교체가 원자적이어야 두 요청이 동시에 회전해도 하나만 성공한다.
     * false 는 "저장된 jti 가 다르다(이미 회전됨 = 재사용)" 또는 "키가 없다(로그아웃·만료)" 다. 호출자는 둘 다 세션 폐기로 다룬다.
     */
    boolean rotate(UUID sessionId, UUID expectedRefreshTokenId, UUID newRefreshTokenId, Duration ttl);

    /** 로그아웃·재사용 탐지 때. */
    void delete(UUID sessionId);
}
