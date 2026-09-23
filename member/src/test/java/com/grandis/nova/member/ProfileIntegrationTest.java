package com.grandis.nova.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.common.security.JwtAuthenticationFilter;
import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.common.security.TokenType;
import com.grandis.nova.common.web.RequestIdFilter;
import com.grandis.nova.member.customer.Customer;
import com.grandis.nova.member.customer.CustomerRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * GET/PUT /me/profile — 카카오가 주지 않는 이름·이메일·연락처를 회원이 직접 입력한다. 실제 MySQL.
 * 카카오 없이 회원 행을 직접 만들고 발급기로 USER 토큰을 만든다. Redis 는 필터의 폐기 조회에 쓰인다(없으면 skip).
 */
@SpringBootTest(classes = MemberApplication.class, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/shop?serverTimezone=UTC&characterEncoding=UTF-8",
        "spring.datasource.username=nova", "spring.datasource.password=nova-local",
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_READ_COMMITTED",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.data.redis.host=localhost", "spring.data.redis.port=6379",
        "jwt.issuer=nova-test",
        "jwt.access-token-validity=30m", "jwt.refresh-token-validity=14d",
        "kakao.client-id=cid", "kakao.client-secret=csecret",
        "kakao.token-uri=https://kauth.kakao.com/oauth/token", "kakao.user-info-uri=https://kapi.kakao.com/v2/user/me",
        "kakao.allowed-redirect-uris=http://localhost:3000/login/kakao/callback",
        "auth.refresh.allowed-origins=http://localhost:3000",
        "admin.username=admin", "admin.password-hash=$2a$12$R9h/cIPz0gi.URNNX3kh2OPST9/PgBkqquzi.Ss7KIUgO2t0jWMUW",
        "auth.cookie.secure=false"
})
@DisplayName("/me/profile (실제 MySQL)")
class ProfileIntegrationTest {

    private static final String PATH = "/api/v1/me/profile";
    private static final String FULL = "{\"name\":\"홍길동\",\"email\":\"hong@example.com\",\"phoneNumber\":\"010-1234-5678\"}";

    @org.springframework.test.context.DynamicPropertySource
    static void keys(org.springframework.test.context.DynamicPropertyRegistry registry) {
        TestKeys.register(registry);
    }

    @BeforeAll
    static void requireInfra() {
        TestInfra.requirePort(3306, "MySQL");
        TestInfra.requirePort(6379, "Redis");
    }

    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy springSecurityFilterChain;
    @Autowired RequestIdFilter requestIdFilter;
    @Autowired JwtTokenProvider provider;
    @Autowired CustomerRepository customers;
    @Autowired JdbcTemplate jdbc;

    private MockMvc mvc;
    private Customer customer;
    private String userToken;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter, springSecurityFilterChain).build();
        customer = customers.saveAndFlush(Customer.fromKakao("t" + UUID.randomUUID().toString().replace("-", "").substring(0, 18), "카카오닉네임"));
        userToken = provider.create(String.valueOf(customer.getId()), Role.USER, UUID.randomUUID(), TokenType.ACCESS);
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("SELECT name, email, phone_number FROM customers WHERE id = ?", customer.getId());
    }

    private static String body(String name, String email, String phone) {
        return "{\"name\":%s,\"email\":%s,\"phoneNumber\":%s}".formatted(json(name), json(email), json(phone));
    }

    private static String json(String v) {
        return v == null ? "null" : "\"" + v + "\"";
    }

    @Test
    @DisplayName("가입 직후에는 셋 다 null 이고 표시 이름만 있다 — 카카오가 닉네임 말고는 주지 않는다")
    void freshCustomerHasOnlyDisplayName() throws Exception {
        mvc.perform(get(PATH).header(JwtAuthenticationFilter.HEADER, userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("카카오닉네임"))
                .andExpect(jsonPath("$.data.name").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.email").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.phoneNumber").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("PUT 전체 → 200 에 저장된 값, GET 왕복 같음, DB 세 칸이 채워짐. 표시 이름은 안 바뀐다")
    void putThenGetRoundTrip() throws Exception {
        mvc.perform(put(PATH).header(JwtAuthenticationFilter.HEADER, userToken).contentType(MediaType.APPLICATION_JSON).content(FULL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("홍길동"))
                .andExpect(jsonPath("$.data.email").value("hong@example.com"))
                .andExpect(jsonPath("$.data.phoneNumber").value("010-1234-5678"))
                .andExpect(jsonPath("$.data.displayName").value("카카오닉네임"));

        mvc.perform(get(PATH).header(JwtAuthenticationFilter.HEADER, userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("hong@example.com"));
        assertThat(row()).containsEntry("name", "홍길동").containsEntry("email", "hong@example.com")
                .containsEntry("phone_number", "010-1234-5678");
        assertThat(jdbc.queryForObject("SELECT display_name FROM customers WHERE id = ?", String.class, customer.getId()))
                .isEqualTo("카카오닉네임");
    }

    @Test
    @DisplayName("안 보낸 칸과 빈 문자열은 비운다 — 부분 갱신이 아니라 통째 교체다")
    void omittedAndBlankFieldsAreCleared() throws Exception {
        mvc.perform(put(PATH).header(JwtAuthenticationFilter.HEADER, userToken).contentType(MediaType.APPLICATION_JSON).content(FULL))
                .andExpect(status().isOk());

        // 이름만 보낸다 → 나머지 둘은 비워진다
        mvc.perform(put(PATH).header(JwtAuthenticationFilter.HEADER, userToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"김철수\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("김철수"))
                .andExpect(jsonPath("$.data.email").value(org.hamcrest.Matchers.nullValue()));
        assertThat(row()).containsEntry("name", "김철수").containsEntry("email", null).containsEntry("phone_number", null);

        // 빈 문자열·공백도 null 로 저장된다 (varchar 에 빈 문자열을 섞지 않는다)
        mvc.perform(put(PATH).header(JwtAuthenticationFilter.HEADER, userToken).contentType(MediaType.APPLICATION_JSON)
                        .content(body("   ", "", null)))
                .andExpect(status().isOk());
        assertThat(row()).containsEntry("name", null).containsEntry("email", null);
    }

    @Test
    @DisplayName("이메일 형식이 아니면 400 VALIDATION_FAILED 에 그 칸 이름. 기존 값은 그대로다")
    void invalidEmailIsRejected() throws Exception {
        mvc.perform(put(PATH).header(JwtAuthenticationFilter.HEADER, userToken).contentType(MediaType.APPLICATION_JSON).content(FULL))
                .andExpect(status().isOk());

        for (String bad : new String[] {"not-an-email", "hong@", "@example.com", "hong example@x.com"}) {
            mvc.perform(put(PATH).header(JwtAuthenticationFilter.HEADER, userToken).contentType(MediaType.APPLICATION_JSON)
                            .content(body("홍길동", bad, "010-1234-5678")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.error.details.violations[?(@.field == 'email')]").exists());
        }
        assertThat(row()).containsEntry("email", "hong@example.com");   // 거절된 요청은 아무것도 안 바꾼다
    }

    @Test
    @DisplayName("길이는 글자 수로 센다: 이름 50 이모지는 통과, 51 은 400. NFD 한글도 정규화한 뒤 센다")
    void lengthIsCountedInCodePoints() throws Exception {
        String emoji = "😀";   // 😀 char 2개 = 코드포인트 1개
        mvc.perform(put(PATH).header(JwtAuthenticationFilter.HEADER, userToken).contentType(MediaType.APPLICATION_JSON)
                        .content(body(emoji.repeat(50), null, null)))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT CHAR_LENGTH(name) FROM customers WHERE id = ?", Integer.class, customer.getId()))
                .isEqualTo(50);

        mvc.perform(put(PATH).header(JwtAuthenticationFilter.HEADER, userToken).contentType(MediaType.APPLICATION_JSON)
                        .content(body(emoji.repeat(51), null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[?(@.field == 'name')]").exists());

        String nfc = "한국어이름".repeat(10);                                                   // 코드포인트 50
        String nfd = java.text.Normalizer.normalize(nfc, java.text.Normalizer.Form.NFD);       // 정규화 전 130
        assertThat(nfd.codePointCount(0, nfd.length())).isEqualTo(130);
        mvc.perform(put(PATH).header(JwtAuthenticationFilter.HEADER, userToken).contentType(MediaType.APPLICATION_JSON)
                        .content(body(nfd, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value(nfc));
        assertThat(jdbc.queryForObject("SELECT CHAR_LENGTH(name) FROM customers WHERE id = ?", Integer.class, customer.getId()))
                .isEqualTo(50);
    }

    @Test
    @DisplayName("이메일은 RFC 상한(254자)까지 통과하고 그보다 길면 400. 연락처는 20자까지")
    void emailAndPhoneBoundaries() throws Exception {
        // 유효한 이메일의 상한은 254자다(로컬 64 + @ + 도메인, 각 라벨 63). 칸이 varchar(255)라 @CodePointSize 는 그 뒤를 받치는 그물이고
        // 실제 상한은 형식 검사가 먼저 정한다 — 255자짜리 "유효한" 주소는 존재하지 않는다(실측: 255 는 형식에서 거절된다).
        String longest = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61);
        assertThat(longest).hasSize(254);
        mvc.perform(put(PATH).header(JwtAuthenticationFilter.HEADER, userToken).contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, longest, "0".repeat(20))))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT CHAR_LENGTH(email) FROM customers WHERE id = ?", Integer.class, customer.getId()))
                .isEqualTo(254);

        mvc.perform(put(PATH).header(JwtAuthenticationFilter.HEADER, userToken).contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, "a".repeat(64) + "@" + ("b".repeat(63) + ".").repeat(3) + "c".repeat(63), null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[?(@.field == 'email')]").exists());
        mvc.perform(put(PATH).header(JwtAuthenticationFilter.HEADER, userToken).contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, null, "0".repeat(21))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[?(@.field == 'phoneNumber')]").exists());
    }

    @Test
    @DisplayName("연락처는 형식을 보지 않는다 — 국가번호·내선 표기가 나라마다 달라 서버가 정하지 않는다(기본 배송지 연락처와 같은 규칙)")
    void phoneFormatIsNotEnforced() throws Exception {
        for (String phone : new String[] {"+82 10-1234-5678", "010 1234 5678", "01012345678"}) {
            mvc.perform(put(PATH).header(JwtAuthenticationFilter.HEADER, userToken).contentType(MediaType.APPLICATION_JSON)
                            .content(body(null, null, phone)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.phoneNumber").value(phone));
        }
    }

    @Test
    @DisplayName("ADMIN 토큰은 403, 토큰이 없으면 401 — 남의 정보를 보거나 바꿀 길이 없다")
    void adminIs403AndAnonymousIs401() throws Exception {
        String admin = provider.create("admin", Role.ADMIN, UUID.randomUUID(), TokenType.ACCESS);
        mvc.perform(get(PATH).header(JwtAuthenticationFilter.HEADER, admin))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(put(PATH).header(JwtAuthenticationFilter.HEADER, admin).contentType(MediaType.APPLICATION_JSON).content(FULL))
                .andExpect(status().isForbidden());
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(FULL)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("다른 회원의 토큰으로는 그 회원의 정보만 보인다 — 경로에 회원 번호가 없고 토큰의 sub 만 본다")
    void eachTokenSeesItsOwnRow() throws Exception {
        Customer other = customers.saveAndFlush(Customer.fromKakao("t" + UUID.randomUUID().toString().replace("-", "").substring(0, 18), "다른사람"));
        String otherToken = provider.create(String.valueOf(other.getId()), Role.USER, UUID.randomUUID(), TokenType.ACCESS);

        mvc.perform(put(PATH).header(JwtAuthenticationFilter.HEADER, userToken).contentType(MediaType.APPLICATION_JSON).content(FULL))
                .andExpect(status().isOk());

        mvc.perform(get(PATH).header(JwtAuthenticationFilter.HEADER, otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("다른사람"))
                .andExpect(jsonPath("$.data.email").value(org.hamcrest.Matchers.nullValue()));
    }
}
