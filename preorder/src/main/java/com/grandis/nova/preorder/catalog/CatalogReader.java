package com.grandis.nova.preorder.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * catalog 소유 표(products · product_options)의 읽기 전용 조회.
 *
 * 엔티티로 매핑하지 않는다. 쓰기 주인이 catalog 라서 여기 엔티티를 두면 변경 감지로 쓸 수 있는 자리가 생기고,
 * catalog 가 칼럼을 늘릴 때마다 이 모듈의 스키마 검증이 같이 깨진다. 필요한 칸만 SQL 로 읽는다.
 */
@Component
public class CatalogReader {

    private static final String FIND_OPTION = """
            SELECT p.id, p.title, p.sale_mode, p.status,
                   o.id, o.sku, o.title, o.price, o.status
              FROM products p
              JOIN product_options o ON o.product_id = p.id
             WHERE p.id = ? AND o.id = ?
            """;

    private static final RowMapper<OptionSnapshot> SNAPSHOT = (rs, rowNum) -> new OptionSnapshot(
            rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
            rs.getLong(5), rs.getString(6), rs.getString(7), rs.getBigDecimal(8), rs.getString(9));

    private final JdbcTemplate jdbcTemplate;

    public CatalogReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 그 상품의 옵션이 아니면 비어 있다(다른 상품의 옵션 id 를 섞어 보내는 요청). */
    public Optional<OptionSnapshot> findOption(Long productId, Long optionId) {
        return jdbcTemplate.query(FIND_OPTION, SNAPSHOT, productId, optionId).stream().findFirst();
    }
}
