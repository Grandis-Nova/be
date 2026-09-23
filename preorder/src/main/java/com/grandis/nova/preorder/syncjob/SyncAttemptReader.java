package com.grandis.nova.preorder.syncjob;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 시도 기록 읽기. worker 소유 표라 엔티티로 매핑하지 않는다 — 매핑하면 쓰기가 가능한 자리가 생기고,
 * worker 가 칸을 늘릴 때마다 이 모듈의 스키마 검증이 같이 깨진다.
 *
 * 관리자 화면 전용이라 접수 경로에서는 부르지 않는다.
 */
@Component
public class SyncAttemptReader {

    private static final String FIND_BY_JOBS = """
            SELECT sync_job_id, attempt_number, actor, result, http_status, error_code, error_message,
                   started_at, finished_at
              FROM preorder_sync_attempts
             WHERE sync_job_id IN (%s)
             ORDER BY sync_job_id, attempt_number
            """;

    private static final RowMapper<SyncAttempt> ATTEMPT = (rs, rowNum) -> new SyncAttempt(
            rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4),
            rs.getObject(5, Integer.class), rs.getString(6), rs.getString(7),
            rs.getTimestamp(8).toInstant(), instantOrNull(rs.getTimestamp(9)));

    private final JdbcTemplate jdbcTemplate;

    public SyncAttemptReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 작업별 시도 목록(번호 순). 작업이 없으면 빈 Map. */
    public Map<Long, List<SyncAttempt>> findByJobIds(Collection<Long> syncJobIds) {
        if (syncJobIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = syncJobIds.stream().map(id -> "?").collect(Collectors.joining(", "));
        return jdbcTemplate.query(FIND_BY_JOBS.formatted(placeholders), ATTEMPT, syncJobIds.toArray()).stream()
                .collect(Collectors.groupingBy(SyncAttempt::syncJobId));
    }

    private static Instant instantOrNull(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
