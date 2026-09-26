package com.grandis.nova.catalog.option;

import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 옵션 하나가 축마다 고른 값의 묶음. 조합 키 · 표시명 · 필터 JSON · 선택 행이 전부 여기서 나온다 —
 * 호출자가 셋을 따로 넘기면 서로 어긋날 자리가 열리고, DB 는 그 어긋남을 못 막는다(NULL 키끼리는 UNIQUE 가 안 걸린다).
 * preorder 가 filter_attributes 를 예약 스냅샷으로 복사하므로 한 번 어긋나면 되돌릴 수 없다.
 *
 * 검사: 축은 모두 같은 상품 · 축 중복 없음 · 값은 그 축의 값. 완전성(모든 축에 값이 있는가)은 상품의 축 목록을 아는
 * 서비스가 {@link #covers} 로 확인한다.
 */
public final class OptionCombination {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TITLE_SEPARATOR = " / ";
    private static final String KEY_SEPARATOR = "-";

    /** 축 하나에 고른 값 하나. */
    public record Pick(ProductOptionAxis axis, ProductOptionValue value) {
        public Pick {
            Objects.requireNonNull(axis, "axis");
            Objects.requireNonNull(value, "value");
        }
    }

    private final Long productId;
    /** 축 position 순. */
    private final List<Pick> picks;

    private OptionCombination(Long productId, List<Pick> picks) {
        this.productId = productId;
        this.picks = List.copyOf(picks);
    }

    public static OptionCombination of(Long productId, List<Pick> picks) {
        if (picks == null || picks.isEmpty()) {
            throw new IllegalArgumentException("a combination needs at least one pick");
        }
        Set<Long> axisIds = new HashSet<>();
        for (Pick pick : picks) {
            ProductOptionAxis axis = pick.axis();
            if (!Objects.equals(axis.getProductId(), productId)) {
                throw new IllegalArgumentException("axis " + axis.getId() + " belongs to another product");
            }
            if (!axisIds.add(axis.getId())) {
                throw new IllegalArgumentException("axis " + axis.getId() + " picked twice");
            }
            if (!Objects.equals(pick.value().getAxisId(), axis.getId())) {
                throw new IllegalArgumentException("value " + pick.value().getId() + " is not a value of axis " + axis.getId());
            }
        }
        List<Pick> ordered = new ArrayList<>(picks);
        ordered.sort(Comparator.comparingInt(pick -> pick.axis().getPosition()));
        return new OptionCombination(productId, ordered);
    }

    public Long getProductId() {
        return productId;
    }

    public List<Pick> getPicks() {
        return picks;
    }

    /** 이 조합이 상품의 축 전부에 값을 갖는가. 축이 나중에 더해지면 기존 옵션은 여기서 거짓이 된다. */
    public boolean covers(List<ProductOptionAxis> productAxes) {
        Set<Long> picked = picks.stream().map(pick -> pick.axis().getId()).collect(Collectors.toSet());
        return productAxes.stream().map(ProductOptionAxis::getId).allMatch(picked::contains);
    }

    /** 값 id 오름차순을 '-' 로 잇는다. 같은 값 집합이면 순서와 무관하게 같은 키다. DB 가 (product_id, key) UNIQUE 로 같은 조합을 막는다. */
    public String combinationKey() {
        return picks.stream().map(pick -> pick.value().getId()).sorted().map(String::valueOf)
                .collect(Collectors.joining(KEY_SEPARATOR));
    }

    /** 축 순서대로 값 표시명을 " / " 로 잇는다(블랙 / 256GB). */
    public String title() {
        return picks.stream().map(pick -> pick.value().getValue()).collect(Collectors.joining(TITLE_SEPARATOR));
    }

    /** 목록 필터 축(color · storage)의 정규화값 JSON. 없으면 null. preorder 가 접수 때 이 JSON 을 복사한다. */
    public String filterAttributes() {
        return attributesJson(true, ProductOptionValue::getNormalizedValue);
    }

    /** 필터가 아닌 축의 표시값 JSON. 없으면 null. */
    public String displayAttributes() {
        return attributesJson(false, ProductOptionValue::getValue);
    }

    /** 저장된 옵션 id 로 선택 행을 만든다. 옵션 저장 뒤 같은 트랜잭션에서 저장한다. */
    public List<ProductOptionSelection> selections(Long optionId) {
        return picks.stream()
                .map(pick -> ProductOptionSelection.of(productId, optionId, pick.axis().getId(), pick.value().getId()))
                .toList();
    }

    private String attributesJson(boolean filterAxes, java.util.function.Function<ProductOptionValue, String> text) {
        Map<String, String> attributes = new LinkedHashMap<>();
        for (Pick pick : picks) {
            if (pick.axis().isFilterAxis() == filterAxes) {
                attributes.put(pick.axis().getAxisKey(), text.apply(pick.value()));
            }
        }
        return attributes.isEmpty() ? null : JSON.writeValueAsString(attributes);
    }
}
