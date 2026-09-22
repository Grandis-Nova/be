package com.grandis.nova.common;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TypesTest {

    @Test
    void 커서는_여러_키를_왕복한다() {
        String cursor = Cursor.encode("2026-09-03T01:00:00Z", 42L);

        assertThat(Cursor.decode(cursor)).containsExactly("2026-09-03T01:00:00Z", "42");
    }

    @Test
    void 깨진_커서는_400_업무_예외() {
        assertThatThrownBy(() -> Cursor.decode("%%%not-base64"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }

    @Test
    void 오프셋_페이지는_전체_페이지와_다음_여부를_계산한다() {
        OffsetPage<String> page = OffsetPage.of(List.of("a", "b"), 0, 2, 5);

        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.hasNext()).isTrue();
        assertThat(OffsetPage.of(List.of("e"), 2, 2, 5).hasNext()).isFalse();
        assertThat(OffsetPage.of(List.<String>of(), 0, 20, 0).totalPages()).isZero();
    }

    @Test
    void 오프셋_페이지는_잘못된_값을_거절한다() {
        assertThatThrownBy(() -> OffsetPage.of(List.of(), -1, 20, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OffsetPage.of(List.of(), 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 업무_예외의_details_는_복사본이고_없으면_null() {
        Map<String, Object> details = new java.util.HashMap<>(Map.of("fields", List.of("optionId")));
        BusinessException e = new BusinessException(CommonErrorCode.VALIDATION_FAILED, details);
        details.put("later", "change");

        assertThat(e.details()).containsOnlyKeys("fields");
        assertThat(new BusinessException(CommonErrorCode.NOT_FOUND).details()).isNull();
        assertThat(e.getStackTrace()).isEmpty();  // 예상된 실패 — 스택을 채우지 않는다
    }
}
