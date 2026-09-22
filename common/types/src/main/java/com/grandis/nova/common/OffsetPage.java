package com.grandis.nova.common;

import java.util.List;
import java.util.function.Function;

/**
 * 오프셋 페이징 결과. **관리자 목록 전용**이다.
 *
 * 사용자 목록은 {@link CursorPage} 를 쓴다 — 접수가 계속 들어오는 목록에서 오프셋은 페이지를 넘기는 사이에
 * 항목이 밀려 겹치거나 빠진다. 관리자 화면은 전체 건수와 페이지 번호가 필요하고, 조회가 드물어 COUNT 비용을 감당한다.
 *
 * page 는 0 부터 센다.
 */
public record OffsetPage<T>(List<T> items, int page, int size, long total) {

    public OffsetPage {
        if (page < 0 || size < 1 || total < 0) {
            throw new IllegalArgumentException("page=%d size=%d total=%d".formatted(page, size, total));
        }
        items = List.copyOf(items);
    }

    public static <T> OffsetPage<T> of(List<T> items, int page, int size, long total) {
        return new OffsetPage<>(items, page, size, total);
    }

    /** 올림 나눗셈을 몫과 나머지로 한다. total + size - 1 은 total 이 클 때 넘친다. */
    public long totalPages() {
        return total / size + (total % size == 0 ? 0 : 1);
    }

    public boolean hasNext() {
        return ((long) page + 1) * size < total;
    }

    public <R> OffsetPage<R> map(Function<T, R> mapper) {
        return new OffsetPage<>(items.stream().map(mapper).toList(), page, size, total);
    }
}
