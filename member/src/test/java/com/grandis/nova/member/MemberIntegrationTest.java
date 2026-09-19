package com.grandis.nova.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.common.security.JwtAuthenticationFilter;
import com.grandis.nova.common.web.MdcContextFilter;
import com.grandis.nova.member.auth.api.AuthCookies;
import com.grandis.nova.member.auth.application.KakaoLoginService;
import com.grandis.nova.member.auth.infrastructure.kakao.KakaoOAuthClient;
import com.grandis.nova.member.auth.infrastructure.kakao.KakaoUserInfo;
import com.grandis.nova.member.customer.CustomerRepository;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 07 §1 단계 7: 첫 로그인 → customers INSERT + 토큰 / 두 번째 → INSERT 없음 / 동시 첫 로그인 2개 → 행 1개 / 재발급 / 로그아웃 후 재발급 → 401.
 * 실제 MySQL(localhost:3306, shop, nova/nova-local)·실제 Redis(6379). 카카오만 모킹. 둘 중 하나라도 없으면 skip.
 * 이것이 07 §0-3 의 첫 기동이다 — ddl-auto=validate 로 Customer 엔티티가 DDL 과 맞는지 컨텍스트 기동에서 확인된다.
 */
@SpringBootTest(classes = MemberApplication.class, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/shop?serverTimezone=UTC&characterEncoding=UTF-8",
        "spring.datasource.username=nova",
        "spring.datasource.password=nova-local",
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_READ_COMMITTED",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
        "spring.jpa.open-in-view=false",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "spring.data.redis.timeout=300ms",
        "spring.data.redis.connect-timeout=200ms",
        "jwt.issuer=nova-test",
        "jwt.secret=nova-test-signing-key-not-a-secret-xxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
        "jwt.access-token-validity=1h",
        "jwt.refresh-token-validity=14d",
        "kakao.client-id=cid",
        "kakao.client-secret=csecret",
        "kakao.token-uri=https://kauth.kakao.com/oauth/token",
        "kakao.user-info-uri=https://kapi.kakao.com/v2/user/me",
        "kakao.allowed-redirect-uris=http://localhost:3000/login/kakao/callback",
        "admin.username=admin",
        "auth.cookie.secure=false"
})
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("member 통합 (실제 MySQL·Redis, 카카오 모킹)")
class MemberIntegrationTest {

    static final String ADMIN_PASSWORD = "correct horse battery staple";
    static final String REDIRECT = "http://localhost:3000/login/kakao/callback";

    @DynamicPropertySource
    static void adminHash(DynamicPropertyRegistry registry) {
        registry.add("admin.password-hash", () -> new BCryptPasswordEncoder(12).encode(ADMIN_PASSWORD));   // D-4: cost 12 이상만 바인딩된다
    }

    @BeforeAll
    static void requireInfra() {
        TestInfra.requirePort(3306, "MySQL");
        TestInfra.requirePort(6379, "Redis");
    }

    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy springSecurityFilterChain;
    @Autowired MdcContextFilter mdcContextFilter;
    @Autowired CustomerRepository customers;
    @Autowired KakaoLoginService loginService;
    @MockitoBean KakaoOAuthClient kakao;

    private MockMvc mvc;
    private String kakaoId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(mdcContextFilter, springSecurityFilterChain).build();
        kakaoId = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 18);   // 회원 행은 시험마다 새로. 지우지 않는다(로컬 일회용 DB)
        when(kakao.exchangeCode(any(), eq(REDIRECT))).thenReturn("kakao-at");
        when(kakao.fetchUser("kakao-at")).thenReturn(new KakaoUserInfo(kakaoId, "홍길동", null));
    }

    private MvcResult login() throws Exception {
        return mvc.perform(post("/api/v1/auth/kakao/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"c\",\"redirectUri\":\"" + REDIRECT + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    private static String json(MvcResult r, String field) throws Exception {
        String body = r.getResponse().getContentAsString();
        return new tools.jackson.databind.ObjectMapper().readTree(body).at(field).asString();
    }

    private static Cookie refreshCookie(MvcResult r) {
        return r.getResponse().getCookie(AuthCookies.REFRESH_TOKEN);
    }

    @Test
    @DisplayName("첫 로그인: customers 에 행이 생기고 sessionToken·displayName·role 과 refresh 쿠키가 온다. 내부 PK·kakao_id 는 없다")
    void firstLoginCreatesCustomerAndIssuesTokens() throws Exception {
        assertThat(customers.findByKakaoId(kakaoId)).isEmpty();

        MvcResult r = login();

        assertThat(customers.findByKakaoId(kakaoId)).isPresent();
        String body = r.getResponse().getContentAsString();
        assertThat(json(r, "/success")).isEqualTo("true");
        assertThat(json(r, "/data/sessionToken")).isNotBlank();
        assertThat(json(r, "/data/displayName")).isEqualTo("홍길동");
        assertThat(json(r, "/data/role")).isEqualTo("USER");
        assertThat(json(r, "/timestamp")).endsWith("Z");                  // api-spec: ISO8601 UTC
        assertThat(body).contains("\"error\":null");                       // 봉투는 명시적 null 이 계약. Jackson 3 기본 inclusion=ALWAYS (실측)
        assertThat(body).doesNotContain(kakaoId).doesNotContain("customerId").doesNotContain("\"id\"");
        Cookie c = refreshCookie(r);
        assertThat(c).isNotNull();
        assertThat(c.isHttpOnly()).isTrue();
        assertThat(c.getPath()).isEqualTo(AuthCookies.PATH);
        assertThat(c.getMaxAge()).isEqualTo(14 * 24 * 3600);
        // D-6 다섯 요소 중 나머지 둘. 이 컨텍스트는 auth.cookie.secure=false 라 Secure 가 없어야 한다(true 쪽은 ConfigBindingTest)
        String setCookie = r.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains("SameSite=Lax").doesNotContainIgnoringCase("secure");
    }

    @Test
    @DisplayName("두 번째 로그인: INSERT 없이 같은 행, 새 sid")
    void secondLoginReusesRow() throws Exception {
        login();
        long before = customers.count();
        Long id = customers.findByKakaoId(kakaoId).orElseThrow().getId();

        login();

        assertThat(customers.count()).isEqualTo(before);
        assertThat(customers.findByKakaoId(kakaoId).orElseThrow().getId()).isEqualTo(id);
    }

    @Test
    @DisplayName("실측: 같은 카카오 회원의 동시 첫 로그인 8개 → 행 1개, 전부 성공, 그리고 1062 재조회 분기에 실제로 들어갔다(로그 횟수 ≥ 1)")
    void concurrentFirstLoginCreatesOneRow(CapturedOutput output) throws Exception {
        int n = 8;
        // 카카오 조회를 흉내 내는 자리에서 n 개를 모아 같이 출발시킨다 — 조회(없음)→INSERT 창에 여럿이 같이 들어가게
        CyclicBarrier gate = new CyclicBarrier(n);
        when(kakao.fetchUser("kakao-at")).thenAnswer(inv -> {
            gate.await(10, TimeUnit.SECONDS);
            return new KakaoUserInfo(kakaoId, "홍길동", null);
        });
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        java.util.List<Future<KakaoLoginService.LoginResult>> results = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return loginService.login("c", REDIRECT);
                }));
            }
            start.countDown();
            for (Future<KakaoLoginService.LoginResult> f : results) {
                assertThat(f.get(10, TimeUnit.SECONDS).displayName()).isEqualTo("홍길동");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(customers.findAll().stream().filter(c -> c.getKakaoId().equals(kakaoId)).count()).isEqualTo(1);
        // catch (DataIntegrityViolationException) 에 들어간 횟수. 0 이면 직렬화돼서 분기를 안 밟은 것이고 이 시험은 실측이 아니다
        long lostRaces = output.getOut().lines().filter(l -> l.contains("customers insert lost the race on kakao_id")).count();
        assertThat(lostRaces).as("1062 재조회 분기 진입 횟수").isBetween(1L, (long) n - 1);
    }

    @Test
    @DisplayName("GET /session: 액세스 토큰으로 displayName·role. 토큰 없으면 401 봉투")
    void sessionEndpoint() throws Exception {
        MvcResult r = login();
        String access = json(r, "/data/sessionToken");

        mvc.perform(get("/api/v1/session").header(JwtAuthenticationFilter.HEADER, access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("홍길동"))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.sessionToken").doesNotExist());   // api-spec GET /session data 는 {displayName, role} 둘
        mvc.perform(get("/api/v1/session"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("재발급: 쿠키만으로 200, 새 sessionToken 과 새 refresh 쿠키. 옛 리프레시를 다시 내면 재사용 탐지로 401")
    void refreshRotatesAndDetectsReuse() throws Exception {
        MvcResult r = login();
        Cookie first = refreshCookie(r);

        MvcResult rotated = mvc.perform(post("/api/v1/session/refresh").cookie(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionToken").isNotEmpty())
                .andExpect(jsonPath("$.data.displayName").value("홍길동"))
                .andExpect(cookie().exists(AuthCookies.REFRESH_TOKEN))
                .andReturn();
        assertThat(refreshCookie(rotated).getValue()).isNotEqualTo(first.getValue());

        mvc.perform(post("/api/v1/session/refresh").cookie(first))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        // 재사용 탐지는 세션을 통째로 끊는다: 새 리프레시도 죽는다
        mvc.perform(post("/api/v1/session/refresh").cookie(refreshCookie(rotated))).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("로그아웃 뒤: 재발급 401, 그 액세스 토큰도 401 (sid 폐기 표식), 쿠키는 만료로 내려온다")
    void logoutRevokesSession() throws Exception {
        MvcResult r = login();
        String access = json(r, "/data/sessionToken");
        Cookie refresh = refreshCookie(r);

        mvc.perform(delete("/api/v1/session").header(JwtAuthenticationFilter.HEADER, access))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(AuthCookies.REFRESH_TOKEN, 0));

        mvc.perform(post("/api/v1/session/refresh").cookie(refresh)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/session").header(JwtAuthenticationFilter.HEADER, access)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("관리자 로그인: 맞으면 role ADMIN 토큰 + 쿠키(data 는 sessionToken·role 둘), 그 토큰으로 GET /session 이 ADMIN. 틀리면 401 INVALID_CREDENTIALS")
    void adminLogin() throws Exception {
        MvcResult ok = mvc.perform(post("/api/v1/admin/session").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("ADMIN"))
                .andExpect(jsonPath("$.data.sessionToken").isNotEmpty())
                .andExpect(jsonPath("$.data.displayName").doesNotExist())   // api-spec POST /admin/session data 는 {sessionToken, role} 둘
                .andReturn();
        String admin = json(ok, "/data/sessionToken");
        mvc.perform(get("/api/v1/session").header(JwtAuthenticationFilter.HEADER, admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("ADMIN"));

        mvc.perform(post("/api/v1/admin/session").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
        mvc.perform(post("/api/v1/admin/session").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"root\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("카카오가 거절하면(허용 목록 밖 redirectUri) 400 INVALID_OAUTH_CALLBACK 봉투이고 회원은 안 생긴다")
    void kakaoFailureIs400() throws Exception {
        when(kakao.exchangeCode(any(), eq("https://evil.example/cb")))
                .thenThrow(new com.grandis.nova.common.BusinessException(com.grandis.nova.member.auth.AuthErrorCode.INVALID_OAUTH_CALLBACK));

        mvc.perform(post("/api/v1/auth/kakao/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"c\",\"redirectUri\":\"https://evil.example/cb\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OAUTH_CALLBACK"))
                .andExpect(header().exists(MdcContextFilter.TRACE_ID_HEADER));
        assertThat(customers.findByKakaoId(kakaoId)).isEmpty();
    }
}
