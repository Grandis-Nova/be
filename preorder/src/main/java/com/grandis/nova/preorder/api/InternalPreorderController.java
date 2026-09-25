package com.grandis.nova.preorder.api;

import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.preorder.query.PayabilityService;
import com.grandis.nova.preorder.web.CurrentViewer;
import com.grandis.nova.preorder.web.Viewer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 서비스끼리만 부르는 API. 호출한 쪽이 사용자 토큰을 그대로 전달한다. */
@RestController
@RequestMapping("/internal/preorders")
public class InternalPreorderController {

    private final PayabilityService payabilityService;

    public InternalPreorderController(PayabilityService payabilityService) {
        this.payabilityService = payabilityService;
    }

    @GetMapping("/{preorderId}/payability")
    public ApiResponse<PayabilityResponse> payability(@CurrentViewer Viewer viewer, @PathVariable String preorderId) {
        return ApiResponse.ok(PayabilityResponse.from(payabilityService.check(viewer, preorderId)));
    }
}
