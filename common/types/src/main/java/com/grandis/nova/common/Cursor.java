package com.grandis.nova.common;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 커서 인코딩.
 *
 * 값을 그대로 노출하지 않으려고 Base64 로 감싼다. 암호화가 아니므로
 * 민감한 값을 커서에 담지 않는다 — 누구나 디코딩할 수 있다.
 *
 * 정렬 키가 둘 이상이면(예: created_at, id) 구분자로 이어 붙인다.
 * 단일 키로 정렬하면 같은 값이 여러 행에 있을 때 페이지 경계에서 항목이 새거나 겹친다.
 */
public final class Cursor {

    private static final String DELIMITER = "|";

    private Cursor() {
    }

    public static String encode(Object... keys) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) {
                sb.append(DELIMITER);
            }
            sb.append(keys[i]);
        }
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 클라이언트가 보낸 값이므로 깨져 있을 수 있다. 디코딩에 실패하면 500 이 아니라 400 이다.
     */
    public static String[] decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            return raw.split("\\" + DELIMITER, -1);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED, "커서가 올바르지 않습니다.");
        }
    }
}
