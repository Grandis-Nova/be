package com.grandis.nova.common;

import java.util.List;
import java.util.function.Function;

/**
 * 커서 페이징 결과.
 *
 * 오프셋 페이징을 쓰지 않는 이유 — 접수가 계속 들어오는 목록에서 OFFSET 은
 * 페이지를 넘기는 사이에 앞쪽이 밀려 같은 항목이 두 번 나오거나 건너뛴다.
 * 커서는 "이 지점 다음부터" 라서 그런 일이 없다.
 *
 * nextCursor 가 null 이면 마지막 페이지다. 전체 건수는 담지 않는다 —
 * 커서 페이징에서 총계를 내려면 매번 COUNT 를 돌려야 하고, 그게 페이징으로 아낀 비용을 되돌린다.
 */
public record CursorPage<T>(List<T> items, String nextCursor) {

    public static <T> CursorPage<T> of(List<T> items, String nextCursor) {
        return new CursorPage<>(items, nextCursor);
    }

    public static <T> CursorPage<T> last(List<T> items) {
        return new CursorPage<>(items, null);
    }

    public boolean hasNext() {
        return nextCursor != null;
    }

    public <R> CursorPage<R> map(Function<T, R> mapper) {
        return new CursorPage<>(items.stream().map(mapper).toList(), nextCursor);
    }
}
