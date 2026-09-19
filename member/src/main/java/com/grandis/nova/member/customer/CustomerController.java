package com.grandis.nova.member.customer;

import com.grandis.nova.common.security.CurrentCustomerId;
import com.grandis.nova.common.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * api-spec F-X-01 끝. 권한 USER — ADMIN 토큰은 @CurrentCustomerId 리졸버가 403 FORBIDDEN 으로 막는다.
 * PUT 은 다섯 칸 전체 교체다(부분 갱신 없음).
 */
@RestController
@RequestMapping("/api/v1/me/default-address")
public class CustomerController {

    private final CustomerService customers;

    public CustomerController(CustomerService customers) {
        this.customers = customers;
    }

    @GetMapping
    public ApiResponse<DefaultAddressResponse> get(@CurrentCustomerId Long customerId) {
        return ApiResponse.ok(new DefaultAddressResponse(customers.defaultAddress(customerId)));
    }

    @PutMapping
    public ApiResponse<DefaultAddressResponse> put(@CurrentCustomerId Long customerId, @Valid @RequestBody DefaultAddressRequest request) {
        return ApiResponse.ok(new DefaultAddressResponse(customers.changeDefaultAddress(customerId, request.toAddress())));
    }
}
