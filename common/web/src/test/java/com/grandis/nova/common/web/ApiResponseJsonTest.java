package com.grandis.nova.common.web;

import com.grandis.nova.common.CommonErrorCode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계약(contracts/openapi.yaml 의 ApiResponse)과 같은 JSON 이 나가는지 본다.
 * 값이 없는 필드도 null 로 나가야 하고, 시각은 ISO-8601 문자열이어야 한다.
 */
class ApiResponseJsonTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void 성공_봉투는_다섯_칸이_모두_있고_error_는_null() {
        JsonNode json = mapper.valueToTree(ApiResponse.ok(Map.of("preorderId", "p-1")));

        assertThat(json.propertyNames()).containsExactlyInAnyOrder("success", "data", "error", "timestamp", "traceId");
        assertThat(json.get("success").asBoolean()).isTrue();
        assertThat(json.get("data").get("preorderId").asString()).isEqualTo("p-1");
        assertThat(json.get("error").isNull()).isTrue();
        assertThat(json.get("traceId").isNull()).isTrue();
        assertThat(json.get("timestamp").isString()).isTrue();
        assertThat(json.get("timestamp").asString()).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
    }

    @Test
    void 실패_봉투는_data_가_null_이고_error_에_세_칸() {
        JsonNode json = mapper.valueToTree(ApiResponse.fail(CommonErrorCode.NOT_FOUND, "없음"));

        assertThat(json.get("success").asBoolean()).isFalse();
        assertThat(json.get("data").isNull()).isTrue();
        JsonNode error = json.get("error");
        assertThat(error.propertyNames()).containsExactlyInAnyOrder("code", "message", "details");
        assertThat(error.get("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(error.get("details").isNull()).isTrue();
    }
}
