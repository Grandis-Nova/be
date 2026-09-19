package com.grandis.nova.member.auth.api;

import com.grandis.nova.common.security.AuthenticatedPrincipal;
import com.grandis.nova.common.security.InvalidTokenException;
import com.grandis.nova.common.security.JwtAuthenticationFilter;
import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.NovaAuthentication;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.common.security.TokenClaims;
import com.grandis.nova.common.security.TokenType;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.member.auth.application.AdminLoginService;
import com.grandis.nova.member.auth.application.KakaoLoginService;
import com.grandis.nova.member.auth.application.TokenService;
import com.grandis.nova.member.customer.CustomerRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * api-spec F-X-01. 경로·응답 필드·오류 코드는 계약 그대로다.
 *
 * - POST /auth/kakao/callback {code, redirectUri} → 200 {sessionToken, displayName, role} + 쿠키 refresh_token.
 *   D-1(프론트 주도): state 는 프론트가 만들고 프론트가 검증한다(08 D-1 완료 조건 c). 서버는 redirectUri 가 허용 목록에 있는지만 본다.
 * - POST /session/refresh (쿠키) → 200 같은 모양 + 새 쿠키. 공개 경로. 폐기 검사는 TokenService.rotate. displayName 을 내야 하므로 customers 를 PK 로 1회 읽는다(06 §3).
 * - GET /session → {displayName, role}. USER 면 customers 에서 이름을 읽는다.
 * - DELETE /session → 204 + 쿠키 삭제. D-2: Redis 가 죽어 표식을 못 심어도 204. 그때의 창은 TokenService.revoke 주석과 08 D-2. WARN 으로 남긴다.
 * - POST /admin/session {username, password} → 200 {sessionToken, role: ADMIN} + 쿠키 / 401 INVALID_CREDENTIALS. D-4.
 */
@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final KakaoLoginService kakaoLogin;
    private final AdminLoginService adminLogin;
    private final TokenService tokens;
    private final JwtTokenProvider provider;
    private final CustomerRepository customers;
    private final AuthCookies cookies;

    public AuthController(KakaoLoginService kakaoLogin, AdminLoginService adminLogin, TokenService tokens,
                          JwtTokenProvider provider, CustomerRepository customers, AuthCookies cookies) {
        this.kakaoLogin = kakaoLogin;
        this.adminLogin = adminLogin;
        this.tokens = tokens;
        this.provider = provider;
        this.customers = customers;
        this.cookies = cookies;
    }

    public record KakaoCallbackRequest(@NotBlank String code, @NotBlank String redirectUri) {
    }

    public record AdminLoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    @PostMapping("/auth/kakao/callback")
    public ResponseEntity<ApiResponse<LoginResponse>> kakaoCallback(@Valid @RequestBody KakaoCallbackRequest request) {
        KakaoLoginService.LoginResult result = kakaoLogin.login(request.code(), request.redirectUri());
        return withRefreshCookie(result.tokens(), new LoginResponse(result.tokens().accessToken(), result.displayName(), result.role()));
    }

    @PostMapping("/session/refresh")
    public ResponseEntity<ApiResponse<LoginResponse>> refresh(
            @CookieValue(name = AuthCookies.REFRESH_TOKEN, required = false) String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new InvalidTokenException("refresh cookie missing");
        }
        TokenService.IssuedTokens rotated = tokens.rotate(refreshToken);
        TokenClaims access = provider.parse(rotated.accessToken());
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(access.subject(), access.role());
        return withRefreshCookie(rotated, new LoginResponse(rotated.accessToken(), displayNameOf(principal), access.role()));
    }

    @GetMapping("/session")
    public ApiResponse<SessionInfoResponse> session(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ApiResponse.ok(new SessionInfoResponse(displayNameOf(principal), principal.role()));
    }

    @DeleteMapping("/session")
    public ResponseEntity<Void> logout(@RequestHeader(JwtAuthenticationFilter.HEADER) String accessToken) {
        TokenClaims claims = provider.parse(accessToken);   // 필터가 이미 통과시킨 토큰이라 여기서 실패하지 않는다
        try {
            tokens.revoke(claims);
        } catch (DataAccessException e) {
            // D-2 "DELETE /session 은 열린 경로. 표식을 못 심으니 쿠키만 지우고 204". Redis 예외만 삼킨다 — 다른 예외는 500 으로 드러나야 한다.
            log.warn("logout incomplete, cookie cleared anyway sid={} cause={}", claims.sessionId().toString().substring(0, 8), e.getClass().getSimpleName());
        }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.expiredRefresh().toString()).build();
    }

    @PostMapping("/admin/session")
    public ResponseEntity<ApiResponse<AdminSessionResponse>> adminSession(@Valid @RequestBody AdminLoginRequest request) {
        TokenService.IssuedTokens issued = adminLogin.login(request.username(), request.password());
        return withRefreshCookie(issued, new AdminSessionResponse(issued.accessToken(), Role.ADMIN));
    }

    private <T> ResponseEntity<ApiResponse<T>> withRefreshCookie(TokenService.IssuedTokens issued, T body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.refresh(issued.refreshToken(), issued.refreshTokenMaxAge()).toString())
                .body(ApiResponse.ok(body));
    }

    private String displayNameOf(AuthenticatedPrincipal principal) {
        if (principal.role() != Role.USER) {
            return null;
        }
        long customerId;
        try {
            customerId = principal.customerId();
        } catch (NumberFormatException e) {
            // CurrentCustomerIdArgumentResolver 와 같은 처리: USER 의 sub 는 발급기가 customers.id 로만 만든다. 십진수가 아니면 500 이 아니라 401.
            throw new InvalidTokenException("non-numeric subject for USER");
        }
        return customers.findById(customerId).map(c -> c.getDisplayName()).orElse(null);
    }
}
