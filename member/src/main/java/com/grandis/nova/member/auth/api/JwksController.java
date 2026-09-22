package com.grandis.nova.member.auth.api;

import com.grandis.nova.common.security.JwtKeyRing;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 발급 서비스의 공개키 게시(RFC 7517). 검증 서비스(catalog·preorder·order)가 `jwt.jwk-set-uri` 로 받아 캐시한다.
 * 공개 경로이고 봉투를 씌우지 않는다 — JWKS 는 표준 문서 형식이라 그대로 내야 다른 도구도 읽는다.
 *
 * 키 교체 절차의 정본은 02 §3 이다. 요지: ③ 서명 전환 배포 때 `jwt.public-keys` 를 **옛** 공개키로 바꿔야 한다 — JwtKeyRing 은 서명 kid 를 먼저 넣고
 * public-keys 는 putIfAbsent 라, ② 에 넣어 둔 새 공개키를 그대로 두고 전환하면 옛 kid 가 링에서 사라져 살아 있는 리프레시 14일치가 전부 죽는다
 * (단계 11 리뷰 blocker). 검증 서비스는 주기 갱신이 없어 ②의 "미리 게시" 는 새로 뜨는 인스턴스에만 효과가 있고, 떠 있는 인스턴스는 ③ 뒤 첫 모르는 kid 요청에서 따라잡는다.
 */
@RestController
public class JwksController {

    private final JwtKeyRing keys;

    public JwksController(JwtKeyRing keys) {
        this.keys = keys;
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return keys.jwks();
    }
}
