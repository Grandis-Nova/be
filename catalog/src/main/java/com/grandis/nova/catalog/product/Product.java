package com.grandis.nova.catalog.product;

import com.grandis.nova.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * 상품(모델). 옵션은 {@link ProductOption} 이 product id 로 잇는다.
 *
 * 노출은 세 칸이 따로 정한다 — 등록 완료(product_registrations.completed_at) · visible · status.
 * visible 은 등록 중에는 늘 false 다. 관리자가 고른 값은 등록 기록(requested_visible)이 들고 있다가
 * 완료 때 {@link #changeVisibility} 로 한 번 옮긴다 — 같은 사실을 두 곳이 들고 있지 않게.
 * 가격은 basePrice 가 기준이고 옵션의 price 가 최종가다(기본가 + 값별 추가금, 관리자가 직접 고칠 수 있다).
 * 예약 · 주문은 접수 시점 값을 복사하므로 여기를 고쳐도 과거 거래에 소급되지 않는다.
 * image_url 은 product_images 의 GALLERY 대표로 대체돼 폐기 예정이라 매핑하지 않는다.
 */
@Entity
@Table(name = "products")
public class Product extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long categoryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private SaleMode saleMode;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false)
    private BigDecimal basePrice;

    @Column(columnDefinition = "text")
    private String description;

    /** 관리자 검색 키워드. 목록 검색은 상품명과 이 칸을 부분 일치로 본다. */
    @Column(length = 500)
    private String tags;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SaleStatus status;

    @Column(nullable = false)
    private boolean visible;

    @Column(nullable = false)
    private boolean warrantyOffered;

    @Column(nullable = false)
    private BigDecimal warrantySurcharge;

    protected Product() {
    }

    private Product(Long categoryId, SaleMode saleMode, String title, BigDecimal basePrice, String description,
                    String tags, boolean warrantyOffered, BigDecimal warrantySurcharge) {
        this.categoryId = categoryId;
        this.saleMode = saleMode;
        this.title = title;
        this.basePrice = Amounts.requireWholeWon(basePrice, "basePrice");
        this.description = description;
        this.tags = tags;
        this.status = SaleStatus.ACTIVE;
        this.visible = false;
        this.warrantyOffered = warrantyOffered;
        this.warrantySurcharge = Amounts.requireWholeWon(warrantySurcharge, "warrantySurcharge");
    }

    /**
     * 새 상품. 판매 상태는 ACTIVE, 공개 여부는 false 로 시작한다 — 등록이 끝나기 전에는 공개하지 않는다.
     * 보증을 제공하지 않으면 추가금은 0 이다.
     */
    public static Product register(Long categoryId, SaleMode saleMode, String title, BigDecimal basePrice,
                                   String description, String tags,
                                   boolean warrantyOffered, BigDecimal warrantySurcharge) {
        return new Product(categoryId, saleMode, title, basePrice, description, tags,
                warrantyOffered, warrantyOffered ? warrantySurcharge : BigDecimal.ZERO);
    }

    /** 공개 ↔ 비공개. 등록 완료 때 requested_visible 을 옮기는 곳과 관리자 전환이 부른다. 기존 예약 · 주문에는 손대지 않는다. */
    public void changeVisibility(boolean visible) {
        this.visible = visible;
    }

    public Long getId() {
        return id;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public SaleMode getSaleMode() {
        return saleMode;
    }

    public String getTitle() {
        return title;
    }

    public BigDecimal getBasePrice() {
        return basePrice;
    }

    public String getDescription() {
        return description;
    }

    public String getTags() {
        return tags;
    }

    public SaleStatus getStatus() {
        return status;
    }

    public boolean isVisible() {
        return visible;
    }

    public boolean isWarrantyOffered() {
        return warrantyOffered;
    }

    public BigDecimal getWarrantySurcharge() {
        return warrantySurcharge;
    }
}
