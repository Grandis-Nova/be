package com.grandis.nova.preorder.catalog;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletionException;

/**
 * 접수에 쓸 상품 · 옵션 값. catalog 내부 API 로 상품 단위로 받아 메모리에 둔다.
 *
 * 접수 경로가 catalog 를 부르지 않게 하려는 캐시다. 오픈 직후 몰리는 접수(10초 5,000건)가
 * catalog 의 지연 · 장애에 묶이지 않고, catalog 가 preorder 만큼 늘어날 필요도 없다.
 * 사전예약 상품은 오픈 뒤 기준 정보가 동결되므로(ERD 결정 19 · 29 · 30) 캐시가 낡을 걱정이 작다.
 *
 * - 같은 상품을 동시에 처음 찾으면 한 번만 부른다(Caffeine 의 키별 단일 로딩). 오픈 순간의 몰림이 catalog 로 번지지 않는다.
 * - {@link #REFRESH_AFTER} 가 지나면 뒤에서 다시 받는다. 그동안은 가진 값을 쓰고, 다시 받기가 실패해도 가진 값을 유지한다.
 * - 판매 중지처럼 동결 뒤에도 바뀌는 것은 이벤트를 받아 {@link #evict} 한다.
 */
@Component
public class CatalogReader {

    private static final Logger log = LoggerFactory.getLogger(CatalogReader.class);

    static final Duration REFRESH_AFTER = Duration.ofMinutes(1);
    static final Duration EXPIRE_AFTER = Duration.ofMinutes(30);
    static final long MAXIMUM_PRODUCTS = 1_000;

    private final CatalogClient catalogClient;
    private final LoadingCache<Long, Optional<ProductCatalog>> products;

    public CatalogReader(CatalogClient catalogClient) {
        this.catalogClient = catalogClient;
        this.products = Caffeine.newBuilder()
                .refreshAfterWrite(REFRESH_AFTER)
                .expireAfterWrite(EXPIRE_AFTER)
                .maximumSize(MAXIMUM_PRODUCTS)
                .build(this::load);
    }

    /**
     * 그 상품의 옵션 값. 상품이 없거나 그 상품의 옵션이 아니면 비어 있다.
     *
     * @throws BusinessException DEPENDENCY_UNAVAILABLE — 캐시에 없는데 catalog 가 응답하지 않을 때
     */
    public Optional<OptionSnapshot> findOption(Long productId, Long optionId) {
        return product(productId).flatMap(product -> product.snapshot(optionId));
    }

    /** 이벤트(판매 중지 등)로 값이 바뀐 상품을 비운다. 다음 조회가 catalog 에서 다시 받는다. */
    public void evict(Long productId) {
        products.invalidate(productId);
    }

    private Optional<ProductCatalog> product(Long productId) {
        try {
            return products.get(productId);
        } catch (CompletionException | RestClientException e) {
            log.warn("catalog 상품 조회 실패 productId={}", productId, e);
            throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private Optional<ProductCatalog> load(Long productId) {
        try {
            return Optional.ofNullable(catalogClient.getProduct(productId).data());
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }
}
