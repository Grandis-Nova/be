package com.grandis.nova.preorder.catalog;

import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import com.grandis.nova.preorder.support.ShopFixtures.PreorderProduct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@PreorderIntegrationTest
class CatalogReaderTest {

    @Autowired
    CatalogReader catalogReader;

    @Autowired
    JdbcTemplate jdbcTemplate;

    ShopFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
    }

    @Test
    void 접수에_복사할_상품_옵션_값을_읽는다() {
        PreorderProduct product = fixtures.openPreorderProduct();

        OptionSnapshot snapshot = catalogReader.findOption(product.productId(), product.optionId()).orElseThrow();

        assertThat(snapshot.productTitle()).isEqualTo("Nova 1");
        assertThat(snapshot.optionTitle()).isEqualTo("블랙 / 256GB");
        assertThat(snapshot.price()).isEqualByComparingTo(new BigDecimal("1250000"));
        assertThat(snapshot.isPreorderProduct()).isTrue();
        assertThat(snapshot.isOnSale()).isTrue();
    }

    @Test
    void 다른_상품의_옵션이면_비어_있다() {
        PreorderProduct product = fixtures.openPreorderProduct();
        PreorderProduct other = fixtures.openPreorderProduct();

        assertThat(catalogReader.findOption(product.productId(), other.optionId())).isEmpty();
    }

    @Test
    void 판매_중지된_옵션은_판매_중이_아니다() {
        Long productId = fixtures.product("PREORDER", "ACTIVE");
        Long optionId = fixtures.option(productId, "PAUSED");

        assertThat(catalogReader.findOption(productId, optionId)).get()
                .extracting(OptionSnapshot::isOnSale).isEqualTo(false);
    }

    @Test
    void 일반_상품은_사전예약_상품이_아니다() {
        Long productId = fixtures.product("IN_STOCK", "ACTIVE");
        Long optionId = fixtures.option(productId, "ACTIVE");

        assertThat(catalogReader.findOption(productId, optionId)).get()
                .extracting(OptionSnapshot::isPreorderProduct).isEqualTo(false);
    }
}
