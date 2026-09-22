package com.grandis.nova.preorder.catalog;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.web.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogReaderTest {

    static final Long PRODUCT_ID = 7L;
    static final Long OPTION_ID = 70L;

    @Test
    void 같은_상품은_한_번만_부르고_이후는_캐시에서_읽는다() {
        FakeCatalogClient client = new FakeCatalogClient();
        CatalogReader reader = new CatalogReader(client);

        assertThat(reader.findOption(PRODUCT_ID, OPTION_ID)).get()
                .extracting(OptionSnapshot::optionTitle).isEqualTo("블랙 / 256GB");
        assertThat(reader.findOption(PRODUCT_ID, OPTION_ID)).isPresent();

        assertThat(client.calls.get()).isEqualTo(1);
    }

    @Test
    void 오픈_순간_동시에_몰려도_catalog_는_한_번만_부른다() throws Exception {
        FakeCatalogClient client = new FakeCatalogClient();
        client.delayMillis = 200;
        CatalogReader reader = new CatalogReader(client);
        int requests = 20;
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(requests)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return reader.findOption(PRODUCT_ID, OPTION_ID).isPresent();
                }));
            }
            start.countDown();
            for (Future<Boolean> result : results) {
                assertThat(result.get(10, TimeUnit.SECONDS)).isTrue();
            }
        }

        assertThat(client.calls.get()).isEqualTo(1);
    }

    @Test
    void 그_상품의_옵션이_아니면_비어_있다() {
        CatalogReader reader = new CatalogReader(new FakeCatalogClient());

        assertThat(reader.findOption(PRODUCT_ID, 999L)).isEmpty();
    }

    @Test
    void 없는_상품이면_비어_있다() {
        FakeCatalogClient client = new FakeCatalogClient();
        client.notFound = true;

        assertThat(new CatalogReader(client).findOption(PRODUCT_ID, OPTION_ID)).isEmpty();
    }

    @Test
    void 캐시에_없는데_catalog_가_응답하지_않으면_503_으로_알린다() {
        FakeCatalogClient client = new FakeCatalogClient();
        client.unavailable = true;

        assertThatThrownBy(() -> new CatalogReader(client).findOption(PRODUCT_ID, OPTION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
    }

    @Test
    void 비우면_다음_조회에서_다시_받는다() {
        FakeCatalogClient client = new FakeCatalogClient();
        CatalogReader reader = new CatalogReader(client);
        reader.findOption(PRODUCT_ID, OPTION_ID);

        client.optionStatus = "PAUSED";
        reader.evict(PRODUCT_ID);

        assertThat(reader.findOption(PRODUCT_ID, OPTION_ID)).get()
                .extracting(OptionSnapshot::isOnSale).isEqualTo(false);
        assertThat(client.calls.get()).isEqualTo(2);
    }

    @Test
    void 판매_상태와_판매_방식을_판정한다() {
        CatalogReader reader = new CatalogReader(new FakeCatalogClient());

        OptionSnapshot snapshot = reader.findOption(PRODUCT_ID, OPTION_ID).orElseThrow();

        assertThat(snapshot.isPreorderProduct()).isTrue();
        assertThat(snapshot.isOnSale()).isTrue();
    }

    static class FakeCatalogClient implements CatalogClient {

        final AtomicInteger calls = new AtomicInteger();
        volatile long delayMillis;
        volatile boolean notFound;
        volatile boolean unavailable;
        volatile String optionStatus = "ACTIVE";

        @Override
        public ApiResponse<ProductCatalog> getProduct(Long productId) {
            calls.incrementAndGet();
            if (unavailable) {
                throw new ResourceAccessException("connection refused");
            }
            if (notFound) {
                throw HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null);
            }
            sleep();
            return ApiResponse.ok(new ProductCatalog(productId, "Nova 1", "PREORDER", "ACTIVE", List.of(
                    new ProductCatalog.Option(OPTION_ID, "NOVA-1-BLK-256", "블랙 / 256GB",
                            new BigDecimal("1250000"), optionStatus))));
        }

        private void sleep() {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
