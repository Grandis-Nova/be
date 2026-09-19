package com.grandis.nova.member.customer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * PUT /me/default-address 본문. api-spec: 수령인 1~50 · 전화 1~20 · 우편번호 1~10 · 주소 1~200 필수, 상세주소 최대 200 선택/null.
 * 길이는 DDL 의 varchar 와 같다. 다섯 칸이 한 덩어리다 — 넷 중 하나가 빠지면 DB 에 가기 전에 400 VALIDATION_FAILED.
 */
public record DefaultAddressRequest(
        @NotBlank @Size(max = 50) String name,
        @NotBlank @Size(max = 20) String phone,
        @NotBlank @Size(max = 10) String postalCode,
        @NotBlank @Size(max = 200) String line1,
        @Size(max = 200) String line2
) {
    /** 앞뒤 공백을 지우고, 상세주소가 비면 null 로. */
    public ShippingAddress toAddress() {
        String detail = line2 == null || line2.isBlank() ? null : line2.strip();
        return new ShippingAddress(name.strip(), phone.strip(), postalCode.strip(), line1.strip(), detail);
    }
}
