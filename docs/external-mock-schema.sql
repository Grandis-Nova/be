-- 참고용: 최초 Flyway 도입 당시 docs/schema.sql에서 보존한 Mock 정의.
-- 최신 정의 및 마이그레이션의 소유자는 Mock 저장소다. 이 저장소에서 실행하지 않는다.

-- ============================================================
-- external_mock — 외부 예약 Mock 소유
-- 같은 인스턴스의 별도 database. shop 으로 가는 물리 FK 를 만들지 않는다.
-- ============================================================

-- 키 저장과 등록 원장을 합쳤다. 미등록 취소는 키·상태·취소 시각만 저장한다.
-- 모든 등록/취소는 같은 키 행의 생성·잠금으로 직렬화하고 취소된 행을 재활성화하지 않는다.
CREATE TABLE external_mock.preorder_registrations (
    external_key    varchar(100) COLLATE utf8mb4_bin NOT NULL,
    external_number varchar(100) COLLATE utf8mb4_bin NULL,
    customer_id     bigint       NULL,
    product_id      bigint       NULL,
    sku             varchar(80)  COLLATE utf8mb4_bin NULL,
    status          varchar(20)  NOT NULL,
    confirmed_at    datetime(6)  NULL,
    canceled_at     datetime(6)  NULL,
    PRIMARY KEY (external_key),
    UNIQUE KEY uq_registration_number (external_number),
    CONSTRAINT ck_registration_status CHECK (status IN ('ACTIVE','CANCELED')),
    CONSTRAINT ck_registration_active_fields CHECK (
        status <> 'ACTIVE'
        OR (external_number IS NOT NULL AND customer_id IS NOT NULL
            AND product_id IS NOT NULL AND sku IS NOT NULL AND confirmed_at IS NOT NULL)
    ),
    CONSTRAINT ck_registration_canceled CHECK (status <> 'CANCELED' OR canceled_at IS NOT NULL)
) ENGINE = InnoDB;
