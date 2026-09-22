package com.grandis.nova.preorder.api;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.preorder.web.ValidationFailures;

/** 목록 크기 규칙(계약: 1~100). 사용자 · 관리자 목록이 같은 규칙을 쓴다. */
final class PageSizes {

    static final int DEFAULT = 20;
    static final int MIN = 1;
    static final int MAX = 100;

    private PageSizes() {
    }

    /** @throws BusinessException VALIDATION_FAILED — 범위를 벗어난 size */
    static int require(int size) {
        if (size < MIN || size > MAX) {
            throw ValidationFailures.of("size", "%d~%d 이어야 합니다.".formatted(MIN, MAX));
        }
        return size;
    }

    /** 관리자 목록의 페이지 번호(0 부터). */
    static int requirePage(int page) {
        if (page < 0) {
            throw ValidationFailures.of("page", "0 이상이어야 합니다.");
        }
        return page;
    }
}
