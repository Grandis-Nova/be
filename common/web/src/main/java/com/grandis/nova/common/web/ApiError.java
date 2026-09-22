package com.grandis.nova.common.web;

import java.util.Map;

/**
 * 실패 응답의 error 칸.
 *
 * code 는 바뀌지 않는 사유 코드다. 클라이언트는 이 값으로 분기한다.
 * message 는 사용자에게 그대로 보여도 되는 문구다. 내부 오류 원문을 담지 않는다(FR-U-04).
 *
 * details 는 코드별 추가 정보이며 없으면 null 이다. 필드 목록을 고정하지 않고 Map 으로 둔 이유 —
 * 코드마다 필요한 정보가 다르다(검증 실패는 violations, 중복 예약은 existingPreorderId, 멱등키 충돌은 fields).
 * 고정 필드로 두면 코드가 늘 때마다 이 타입을 고쳐야 하고, 그 변경이 모든 서비스를 다시 빌드시킨다.
 */
public record ApiError(
        String code,
        String message,
        Map<String, Object> details
) {

    /** 검증 실패 한 건. details.violations 의 원소. */
    public record Violation(String field, String message) {
    }
}
