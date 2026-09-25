package com.grandis.nova.preorder.support;

import com.grandis.nova.preorder.accept.AcceptResult;
import com.grandis.nova.preorder.accept.PreorderAcceptService;
import com.grandis.nova.preorder.catalog.CatalogClient;
import com.grandis.nova.preorder.support.ShopFixtures.PreorderProduct;

import java.time.Instant;

import static org.mockito.BDDMockito.given;

/**
 * 조회 테스트용 접수. 상품 · 회차 · 차수를 새로 만들고 catalog 대역을 맞춘 뒤 실제 접수 경로로 예약을 만든다.
 * 회원당 모델 하나이므로 접수마다 새 상품을 쓴다.
 */
public class AcceptFixtures {

    private final PreorderAcceptService acceptService;
    private final ShopFixtures fixtures;
    private final CatalogClient catalogClient;

    public AcceptFixtures(PreorderAcceptService acceptService, ShopFixtures fixtures, CatalogClient catalogClient) {
        this.acceptService = acceptService;
        this.fixtures = fixtures;
        this.catalogClient = catalogClient;
    }

    public AcceptResult accept(Long customerId) {
        return accept(customerId, fixtures.openPreorderProduct());
    }

    /** 같은 상품에 여러 예약을 만들 때 쓴다. */
    public AcceptResult accept(Long customerId, PreorderProduct product) {
        given(catalogClient.getProduct(product.productId())).willReturn(CatalogStubs.preorderProduct(
                product.productId(), CatalogStubs.activeOption(product.optionId())));
        return acceptService.acceptByCustomer(customerId, product.productId(), product.productId(),
                product.optionId(), "fixture-key-" + ShopFixtures.unique(),
                AdmissionTickets.issue(product.productId(), customerId, Instant.now()));
    }

    /** 예약의 공개 UUID. 조회 API 는 이 값으로 부른다. */
    public static String tokenOf(AcceptResult result) {
        return result.preorder().getPreorderToken();
    }
}
