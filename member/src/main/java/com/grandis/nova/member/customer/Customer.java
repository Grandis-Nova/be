package com.grandis.nova.member.customer;

import com.grandis.nova.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * shop.customers (docs/schema.sql, 팀 SQL 그대로). 칸 이름·길이는 DDL 그대로. DDL 의 name·email·phone_number·token_version 은 member 가 아직 안 읽어 매핑하지 않는다(validate 는 DDL 에만 있는 칸을 안 잡는다). ddl-auto=validate 라 어긋나면 기동이 실패한다.
 * 카카오에서 받는 것은 kakao_id·display_name 뿐이다(닉네임 밖의 항목은 카카오 검수가 필요하다). 기본 배송지는 회원이 직접 입력한다.
 */
@Entity
@Table(name = "customers")
public class Customer extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kakao_id", nullable = false, length = 64, updatable = false)
    private String kakaoId;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "default_ship_to_name", length = 50)
    private String defaultShipToName;

    @Column(name = "default_ship_to_phone", length = 20)
    private String defaultShipToPhone;

    @Column(name = "default_ship_to_postal_code", length = 10)
    private String defaultShipToPostalCode;

    @Column(name = "default_ship_to_line1", length = 200)
    private String defaultShipToLine1;

    @Column(name = "default_ship_to_line2", length = 200)
    private String defaultShipToLine2;

    protected Customer() {
    }

    static final String DEFAULT_DISPLAY_NAME = "카카오 회원";
    static final int DISPLAY_NAME_MAX = 100;

    /**
     * 첫 카카오 로그인 = 가입. display_name 은 NOT NULL varchar(100) 이라 카카오에서 못 받은 경우와 넘치는 경우를 여기서 정한다.
     * 닉네임 동의가 없으면 "카카오 회원", 100자를 넘으면 앞 100자. 회원은 나중에 자기 이름으로 바꿀 수 있다(그 기능이 생기면).
     * "100자" 는 코드포인트 기준이다. char 로 자르면 이모지(서러게이트 쌍)가 경계에서 반쪽이 나 조용히 깨진다. MySQL utf8mb4 varchar(100) 도 문자 단위다.
     * 세기 전에 NFC 로 정규화한다 — NFD 한글은 음절 하나가 자모 2~3 코드포인트라 같은 이름이 2.6배로 잡힌다(DefaultAddressRequest 와 같은 자).
     */
    public static Customer fromKakao(String kakaoId, String nickname) {
        Customer c = new Customer();
        c.kakaoId = kakaoId;
        String name = nickname == null ? "" : java.text.Normalizer.normalize(nickname, java.text.Normalizer.Form.NFC).strip();
        if (name.isEmpty()) {
            name = DEFAULT_DISPLAY_NAME;
        }
        c.displayName = truncateToCodePoints(name, DISPLAY_NAME_MAX);
        return c;
    }

    static String truncateToCodePoints(String s, int max) {
        return s.codePointCount(0, s.length()) <= max ? s : s.substring(0, s.offsetByCodePoints(0, max));
    }

    /** 기본 배송지. 미등록이면 null. 넷이 NOT NULL 이면 등록된 것이다(CHECK 가 그 외 조합을 막는다). */
    public ShippingAddress defaultAddress() {
        if (defaultShipToName == null) {
            return null;
        }
        return new ShippingAddress(defaultShipToName, defaultShipToPhone, defaultShipToPostalCode, defaultShipToLine1, defaultShipToLine2);
    }

    /**
     * 다섯 칸을 통째로 바꾼다. 부분 갱신 메서드는 두지 않는다 — CHECK 제약이 부분 입력을 3819 로 막는다(실측: DefaultAddressIntegrationTest).
     * 비우는 길(다섯 칸 전부 NULL, CHECK 의 첫 모양)은 일부러 없다. api-spec 에 DELETE 가 없고, 가입 직후 상태로 되돌릴 요구가 없다. 생기면 여기에 메서드 하나.
     */
    public void changeDefaultAddress(ShippingAddress address) {
        this.defaultShipToName = address.name();
        this.defaultShipToPhone = address.phone();
        this.defaultShipToPostalCode = address.postalCode();
        this.defaultShipToLine1 = address.line1();
        this.defaultShipToLine2 = address.line2();
    }

    public Long getId() {
        return id;
    }

    public String getKakaoId() {
        return kakaoId;
    }

    public String getDisplayName() {
        return displayName;
    }
}
