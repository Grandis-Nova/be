package com.grandis.nova.catalog.image;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {

    List<ProductImage> findByProductIdOrderByKindAscBundleKeyAscPositionAsc(Long productId);
}
