-- nova · 스마트폰 사전예약 시스템 스키마
-- 출처: preorder-erd-v5 (2026-09-17). ERD 가 정본이고 이 파일은 그 옮김이다.
--
-- 전제
--   MySQL 8.0.16 이상. 그 전 버전은 CHECK 를 파싱만 하고 강제하지 않는다.
--   업무 트랜잭션 격리 수준은 READ COMMITTED — 이 파일이 아니라 커넥션 풀에서 설정한다.
--   근거: "없는 행을 잠금 읽기로 확인한 뒤 INSERT" 하는 절차가 REPEATABLE READ 에서
--        갭 락과 삽입 의도 락으로 교착한다(실측 RR 4/4 데드락, RC 4/4 통과).
--   식별자·키 칸은 COLLATE utf8mb4_bin — 대소문자를 구별한다. 기본 콜레이션에 기대지 않는다.
--   부분 인덱스가 없으므로 "조건부 유일" 은 NULL 가능 칸 + UNIQUE 로 표현한다(NULL 끼리는 중복 허용).

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS shop
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS external_mock
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;


-- ============================================================
-- shop — 서비스 소유
-- ============================================================

-- 회원 ------------------------------------------------------
CREATE TABLE shop.customers (
    id                          bigint       NOT NULL AUTO_INCREMENT,
    kakao_id                    varchar(64)  COLLATE utf8mb4_bin NOT NULL,
    display_name                varchar(100) NOT NULL,
    -- 기본 배송지 1개. 주소록 없음. 상세 주소만 따로 비울 수 있다.
    default_ship_to_name        varchar(50)  NULL,
    default_ship_to_phone       varchar(20)  NULL,
    default_ship_to_postal_code varchar(10)  NULL,
    default_ship_to_line1       varchar(200) NULL,
    default_ship_to_line2       varchar(200) NULL,
    created_at                  datetime(6)  NOT NULL,
    updated_at                  datetime(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_customer_kakao (kakao_id),
    CONSTRAINT ck_customer_default_address CHECK (
        (default_ship_to_name IS NULL AND default_ship_to_phone IS NULL
             AND default_ship_to_postal_code IS NULL AND default_ship_to_line1 IS NULL
             AND default_ship_to_line2 IS NULL)
        OR (default_ship_to_name IS NOT NULL AND default_ship_to_phone IS NOT NULL
             AND default_ship_to_postal_code IS NOT NULL AND default_ship_to_line1 IS NOT NULL)
    )
) ENGINE = InnoDB;

-- 카테고리 --------------------------------------------------
-- 1계층 평면. 노출 상태·정렬 순서 칸을 두지 않는다 —
-- 노출은 "그 카테고리에 ACTIVE 상품이 있는가" 로 화면이 판정하고 순서는 id(생성 순)다.
CREATE TABLE shop.categories (
    id                       bigint      NOT NULL AUTO_INCREMENT,
    code                     varchar(40) COLLATE utf8mb4_bin NOT NULL,
    name                     varchar(60) NOT NULL,
    -- [{"key":"caseSize","label":"케이스 크기","order":2}] — 무엇을 받을지만 정하고 값은 담지 않는다.
    option_filter_definitions json       NULL,
    created_at               datetime(6) NOT NULL,
    updated_at               datetime(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_category_code (code)
) ENGINE = InnoDB;

-- 상품 ------------------------------------------------------
CREATE TABLE shop.products (
    id          bigint       NOT NULL AUTO_INCREMENT,
    category_id bigint       NOT NULL,
    sale_mode   varchar(20)  NOT NULL,
    title       varchar(100) NOT NULL,
    description text         NULL,
    image_url   varchar(1000) NULL,
    tags        varchar(500) NULL,
    status      varchar(20)  NOT NULL,
    created_at  datetime(6)  NOT NULL,
    updated_at  datetime(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY ix_product_sale_mode (sale_mode, status),
    KEY ix_product_category (category_id, status, sale_mode),
    CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES shop.categories (id),
    CONSTRAINT ck_product_sale_mode CHECK (sale_mode IN ('PREORDER','IN_STOCK')),
    CONSTRAINT ck_product_status    CHECK (status IN ('ACTIVE','PAUSED'))
) ENGINE = InnoDB;

-- 사전예약 회차 ---------------------------------------------
-- 모델당 1행. 일반 상품에는 이 행이 없다.
-- 접수는 이 행을 잠그므로 상품 정보 수정과 접수가 서로 막지 않는다.
CREATE TABLE shop.preorder_campaigns (
    product_id          bigint      NOT NULL,
    opens_at            datetime(6) NOT NULL,
    closes_at           datetime(6) NOT NULL,
    next_queue_position bigint      NOT NULL DEFAULT 1,
    open_notified_at    datetime(6) NULL,
    created_at          datetime(6) NOT NULL,
    updated_at          datetime(6) NOT NULL,
    PRIMARY KEY (product_id),
    CONSTRAINT fk_campaign_product FOREIGN KEY (product_id) REFERENCES shop.products (id),
    CONSTRAINT ck_campaign_period        CHECK (closes_at > opens_at),
    CONSTRAINT ck_campaign_next_position CHECK (next_queue_position > 0)
) ENGINE = InnoDB;

-- 옵션 ------------------------------------------------------
-- 재고는 일반 상품 전용. 사전예약 옵션은 재고 칸을 0으로 둔다(선점 없음).
-- 가용 = stock_on_hand - stock_reserved - stock_sold.
CREATE TABLE shop.product_variants (
    id                 bigint        NOT NULL AUTO_INCREMENT,
    product_id         bigint        NOT NULL,
    sku                varchar(80)   COLLATE utf8mb4_bin NOT NULL,
    title              varchar(120)  NOT NULL,
    price              decimal(12,0) NOT NULL,
    -- 키는 소속 카테고리의 option_filter_definitions 가 정한다. 코드가 고정하지 않는다.
    filter_attributes  json          NULL,
    display_attributes json          NULL,
    stock_on_hand      int           NOT NULL DEFAULT 0,
    stock_reserved     int           NOT NULL DEFAULT 0,
    stock_sold         int           NOT NULL DEFAULT 0,
    status             varchar(20)   NOT NULL,
    created_at         datetime(6)   NOT NULL,
    updated_at         datetime(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_variant_sku (product_id, sku),
    -- 예약·주문상품의 모델-옵션 복합 FK 대상
    UNIQUE KEY uq_variant_product (product_id, id),
    CONSTRAINT fk_variant_product FOREIGN KEY (product_id) REFERENCES shop.products (id),
    CONSTRAINT ck_variant_price             CHECK (price >= 0),
    CONSTRAINT ck_variant_stock_nonnegative CHECK (stock_on_hand >= 0 AND stock_reserved >= 0 AND stock_sold >= 0),
    -- 관리자 총량 감소는 확보 + 판매 아래로 불가
    CONSTRAINT ck_variant_stock_capacity    CHECK (stock_reserved + stock_sold <= stock_on_hand),
    CONSTRAINT ck_variant_status            CHECK (status IN ('ACTIVE','PAUSED'))
) ENGINE = InnoDB;

-- 배송 차수 -------------------------------------------------
-- 모델 순번의 구간. UNIQUE 는 "최대 1개" 만 보장한다 —
-- 열린 마지막 차수의 존재·구간 공백 없음·중첩 없음은 오픈 전 서버 검사다.
CREATE TABLE shop.shipment_batches (
    id                   bigint NOT NULL AUTO_INCREMENT,
    product_id           bigint NOT NULL,
    batch_number         int    NOT NULL,
    position_from        bigint NOT NULL,
    position_to          bigint NULL,
    -- 상한 없는 차수가 모델당 하나뿐이도록 UNIQUE 를 걸기 위한 자동 계산 칸
    open_ended_marker    tinyint GENERATED ALWAYS AS (CASE WHEN position_to IS NULL THEN 1 ELSE NULL END) STORED,
    estimated_ship_start date   NOT NULL,
    estimated_ship_end   date   NOT NULL,
    created_at           datetime(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_batch_number        (product_id, batch_number),
    UNIQUE KEY uq_batch_position_from (product_id, position_from),
    UNIQUE KEY uq_batch_open_ended    (product_id, open_ended_marker),
    -- 예약의 모델-차수 복합 FK 대상
    UNIQUE KEY uq_batch_product       (product_id, id),
    CONSTRAINT fk_batch_product FOREIGN KEY (product_id) REFERENCES shop.products (id),
    CONSTRAINT ck_batch_positive    CHECK (batch_number > 0 AND position_from > 0),
    CONSTRAINT ck_batch_range       CHECK (position_to IS NULL OR position_to >= position_from),
    CONSTRAINT ck_batch_ship_window CHECK (estimated_ship_end >= estimated_ship_start)
) ENGINE = InnoDB;

-- 장바구니 --------------------------------------------------
-- 일반 상품 전용. 주문과 FK 없음. 가격 미저장.
CREATE TABLE shop.cart_items (
    id          bigint      NOT NULL AUTO_INCREMENT,
    customer_id bigint      NOT NULL,
    variant_id  bigint      NOT NULL,
    quantity    int         NOT NULL,
    created_at  datetime(6) NOT NULL,
    updated_at  datetime(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_cart_customer_variant (customer_id, variant_id),
    KEY ix_cart_variant (variant_id),
    CONSTRAINT fk_cart_customer FOREIGN KEY (customer_id) REFERENCES shop.customers (id),
    CONSTRAINT fk_cart_variant  FOREIGN KEY (variant_id)  REFERENCES shop.product_variants (id),
    CONSTRAINT ck_cart_quantity CHECK (quantity > 0)
) ENGINE = InnoDB;

-- 예약 ------------------------------------------------------
-- 수량은 항상 1이라 칸을 두지 않는다. 결제 여부는 예약이 아니라 주문·payments 가 주인이다.
CREATE TABLE shop.preorders (
    id                     bigint        NOT NULL AUTO_INCREMENT,
    preorder_token         char(36)      COLLATE utf8mb4_bin NOT NULL,
    customer_id            bigint        NOT NULL,
    product_id             bigint        NOT NULL,
    variant_id             bigint        NOT NULL,
    shipment_batch_id      bigint        NOT NULL,
    queue_position         bigint        NOT NULL,
    idempotency_key        varchar(100)  COLLATE utf8mb4_bin NOT NULL,
    product_title_snapshot varchar(100)  NOT NULL,
    variant_title_snapshot varchar(120)  NOT NULL,
    unit_price_snapshot    decimal(12,0) NOT NULL,
    status                 varchar(20)   NOT NULL,
    -- 살아 있으면 1, 취소 완료면 NULL. 이 칸 덕분에 모델당 1인 1건을 UNIQUE 로 막으면서도
    -- 취소 후 재신청이 가능하다.
    active_marker          tinyint       NULL,
    payable_from           datetime(6)   NULL,
    external_reference     varchar(100)  COLLATE utf8mb4_bin NULL,
    internal_note          text          NULL,
    event_sequence         bigint        NOT NULL DEFAULT 0,
    created_at             datetime(6)   NOT NULL,
    updated_at             datetime(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_preorder_token       (preorder_token),
    UNIQUE KEY uq_preorder_external    (external_reference),
    UNIQUE KEY uq_preorder_idempotency (customer_id, idempotency_key),
    -- 취소 행 포함 원장 전체
    UNIQUE KEY uq_preorder_position    (product_id, queue_position),
    -- 모델당 1인 1개. NULL(취소 완료)끼리는 중복 허용
    UNIQUE KEY uq_preorder_active      (customer_id, product_id, active_marker),
    -- 주문의 예약-회원 복합 FK 대상
    UNIQUE KEY uq_preorder_id_customer (id, customer_id),
    KEY ix_preorder_payable            (status, payable_from),
    KEY ix_preorder_customer_created   (customer_id, created_at),
    KEY ix_preorder_variant            (product_id, variant_id),
    KEY ix_preorder_batch              (product_id, shipment_batch_id),
    CONSTRAINT fk_preorder_customer FOREIGN KEY (customer_id) REFERENCES shop.customers (id),
    CONSTRAINT fk_preorder_variant  FOREIGN KEY (product_id, variant_id)
        REFERENCES shop.product_variants (product_id, id),
    CONSTRAINT fk_preorder_batch    FOREIGN KEY (product_id, shipment_batch_id)
        REFERENCES shop.shipment_batches (product_id, id),
    CONSTRAINT ck_preorder_status CHECK (status IN ('PENDING_SYNC','PAYABLE','CANCELING','CANCELED')),
    -- <=> 로 쓴다. active_marker = 1 로 쓰면 NULL 일 때 UNKNOWN 이 되어 CHECK 를 통과해 버린다.
    CONSTRAINT ck_preorder_active CHECK (
        (status =  'CANCELED' AND active_marker IS NULL)
     OR (status <> 'CANCELED' AND active_marker <=> 1)
    ),
    CONSTRAINT ck_preorder_payable_from      CHECK (status <> 'PAYABLE' OR payable_from IS NOT NULL),
    CONSTRAINT ck_preorder_price             CHECK (unit_price_snapshot >= 0),
    CONSTRAINT ck_preorder_position_positive CHECK (queue_position > 0)
) ENGINE = InnoDB;

-- 예약 이력 -------------------------------------------------
-- 추가 전용. 순서는 시각이 아니라 event_sequence 로 판정한다.
CREATE TABLE shop.preorder_events (
    preorder_id    bigint       NOT NULL,
    event_sequence bigint       NOT NULL,
    from_status    varchar(20)  NULL,
    to_status      varchar(20)  NOT NULL,
    actor          varchar(10)  NOT NULL,
    reason         varchar(500) NULL,
    created_at     datetime(6)  NOT NULL,
    PRIMARY KEY (preorder_id, event_sequence),
    CONSTRAINT fk_preorder_event_preorder FOREIGN KEY (preorder_id) REFERENCES shop.preorders (id),
    CONSTRAINT ck_preorder_event_actor        CHECK (actor IN ('USER','ADMIN','SYSTEM')),
    CONSTRAINT ck_preorder_event_admin_reason CHECK (actor <> 'ADMIN' OR reason IS NOT NULL)
) ENGINE = InnoDB;

-- 외부 동기화 작업 ------------------------------------------
-- 예약마다 REGISTER 1건·CANCEL 1건까지. DEAD_LETTER 와 CANCELED 는 REGISTER 전용이다.
CREATE TABLE shop.preorder_sync_jobs (
    id               bigint      NOT NULL AUTO_INCREMENT,
    preorder_id      bigint      NOT NULL,
    job_type         varchar(10) NOT NULL,
    -- 접수 때 고정한다. 재시도마다 같은 내용을 보내야 외부가 중복으로 보지 않는다.
    request_payload  json        NOT NULL,
    status           varchar(20) NOT NULL,
    attempt_count    int         NOT NULL DEFAULT 0,
    next_retry_at    datetime(6) NULL,
    -- 결과 반영은 이 값이 같을 때만. 다르면 아무것도 바꾸지 않는다(리스 만료 후 다른 워커가 판정한 경우).
    lease_token      varchar(64) NULL,
    lease_expires_at datetime(6) NULL,
    dead_lettered_at datetime(6) NULL,
    created_at       datetime(6) NOT NULL,
    updated_at       datetime(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_sync_job_type (preorder_id, job_type),
    KEY ix_sync_job_next_retry  (status, next_retry_at),
    KEY ix_sync_job_lease       (status, lease_expires_at),
    CONSTRAINT fk_sync_job_preorder FOREIGN KEY (preorder_id) REFERENCES shop.preorders (id),
    CONSTRAINT ck_sync_job_type   CHECK (job_type IN ('REGISTER','CANCEL')),
    CONSTRAINT ck_sync_job_status CHECK (status IN ('PENDING','PROCESSING','RETRY_SCHEDULED','SUCCEEDED','DEAD_LETTER','CANCELED')),
    CONSTRAINT ck_sync_job_dead_letter              CHECK (status <> 'DEAD_LETTER' OR dead_lettered_at IS NOT NULL),
    CONSTRAINT ck_sync_job_dead_letter_register_only CHECK (job_type = 'REGISTER' OR (status <> 'DEAD_LETTER' AND dead_lettered_at IS NULL)),
    CONSTRAINT ck_sync_job_canceled_register_only    CHECK (job_type = 'REGISTER' OR status <> 'CANCELED'),
    CONSTRAINT ck_sync_job_attempts CHECK (attempt_count >= 0)
) ENGINE = InnoDB;

-- 동기화 시도 기록 ------------------------------------------
-- 호출 전에 시작 행을 남긴다. 크래시로 결과를 모르는 시도도 행을 유지한다.
--   result NULL + finished_at NULL  → 프로세스가 죽음. 리스 만료 후 복구 워커가 집는다.
--   result UNKNOWN + finished_at 있음 → 호출은 갔고 응답을 못 받음. 조회로 판단하고 재등록하지 않는다.
CREATE TABLE shop.preorder_sync_attempts (
    id             bigint       NOT NULL AUTO_INCREMENT,
    sync_job_id    bigint       NOT NULL,
    attempt_number int          NOT NULL,
    actor          varchar(10)  NOT NULL,
    result         varchar(20)  NULL,
    error_code     varchar(100) NULL,
    error_message  varchar(500) NULL,
    started_at     datetime(6)  NOT NULL,
    finished_at    datetime(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_sync_attempt_number (sync_job_id, attempt_number),
    CONSTRAINT fk_sync_attempt_job FOREIGN KEY (sync_job_id) REFERENCES shop.preorder_sync_jobs (id),
    CONSTRAINT ck_sync_attempt_actor  CHECK (actor IN ('SYSTEM','ADMIN')),
    CONSTRAINT ck_sync_attempt_result CHECK (result IS NULL OR result IN ('SUCCESS','TRANSIENT_FAILURE','REJECTED','UNKNOWN'))
) ENGINE = InnoDB;

-- 주문 ------------------------------------------------------
-- 상태는 한 칸. 결제 성공 = AWAITING_CONFIRMATION(별도 결제 완료 단계 없음).
-- 전이는 모두 현재 상태를 조건으로 한 UPDATE 로만 한다 —
-- 한 칸은 불가능한 조합을 없앨 뿐 잘못된 전이를 막지 않는다.
CREATE TABLE shop.orders (
    id                  bigint        NOT NULL AUTO_INCREMENT,
    order_token         char(36)      COLLATE utf8mb4_bin NOT NULL,
    customer_id         bigint        NOT NULL,
    source              varchar(10)   NOT NULL,
    preorder_id         bigint        NULL,
    status              varchar(30)   NOT NULL,
    total_amount        decimal(12,0) NOT NULL,
    -- 일반 주문 10분 기한. 사전예약 주문은 예약의 24시간 기한을 따르므로 비운다.
    payment_due_at      datetime(6)   NULL,
    -- 재고 반환 1회 표식. 상태를 CANCELED 로 바꾸는 같은 UPDATE 에서 함께 기록한다.
    stock_released_at   datetime(6)   NULL,
    ship_to_name        varchar(50)   NOT NULL,
    ship_to_phone       varchar(20)   NOT NULL,
    ship_to_postal_code varchar(10)   NOT NULL,
    ship_to_line1       varchar(200)  NOT NULL,
    ship_to_line2       varchar(200)  NULL,
    internal_note       text          NULL,
    event_sequence      bigint        NOT NULL DEFAULT 0,
    created_at          datetime(6)   NOT NULL,
    updated_at          datetime(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_order_token    (order_token),
    UNIQUE KEY uq_order_preorder (preorder_id),
    KEY ix_order_due             (status, payment_due_at),
    KEY ix_order_member_created  (customer_id, created_at),
    CONSTRAINT fk_order_customer FOREIGN KEY (customer_id) REFERENCES shop.customers (id),
    -- 예약-회원 일치를 복합 FK 로 강제한다. preorder_id 가 NULL 이면 통과한다.
    CONSTRAINT fk_order_preorder FOREIGN KEY (preorder_id, customer_id)
        REFERENCES shop.preorders (id, customer_id),
    CONSTRAINT ck_order_source CHECK (source IN ('PREORDER','BUY_NOW','CART')),
    CONSTRAINT ck_order_status CHECK (status IN (
        'AWAITING_PAYMENT','AUTHORIZING','AWAITING_CONFIRMATION','PREPARING_ITEMS',
        'READY_TO_SHIP','SHIPPED','DELIVERED','CANCELING','CANCELED')),
    CONSTRAINT ck_order_preorder_link     CHECK ((source = 'PREORDER') = (preorder_id IS NOT NULL)),
    CONSTRAINT ck_order_due               CHECK ((source = 'PREORDER') = (payment_due_at IS NULL)),
    CONSTRAINT ck_order_preorder_no_stock CHECK (source <> 'PREORDER' OR stock_released_at IS NULL),
    CONSTRAINT ck_order_stock_released_canceled CHECK (stock_released_at IS NULL OR status = 'CANCELED'),
    CONSTRAINT ck_order_amount CHECK (total_amount >= 0)
) ENGINE = InnoDB;

-- 주문상품 --------------------------------------------------
-- 부분 취소 없음 → 항목별 상태·반환 칸 없음.
CREATE TABLE shop.order_items (
    id                     bigint        NOT NULL AUTO_INCREMENT,
    order_id               bigint        NOT NULL,
    product_id             bigint        NOT NULL,
    variant_id             bigint        NOT NULL,
    quantity               int           NOT NULL,
    unit_price_snapshot    decimal(12,0) NOT NULL,
    product_title_snapshot varchar(100)  NOT NULL,
    variant_title_snapshot varchar(120)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_order_item_option (order_id, variant_id),
    KEY ix_order_item_variant (product_id, variant_id),
    CONSTRAINT fk_order_item_order   FOREIGN KEY (order_id) REFERENCES shop.orders (id),
    CONSTRAINT fk_order_item_variant FOREIGN KEY (product_id, variant_id)
        REFERENCES shop.product_variants (product_id, id),
    CONSTRAINT ck_order_item_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_item_price    CHECK (unit_price_snapshot >= 0)
) ENGINE = InnoDB;

-- 주문 이력 -------------------------------------------------
-- 추가 전용. 취소 전 배송 단계는 from_status 로 확인한다.
CREATE TABLE shop.order_events (
    order_id       bigint       NOT NULL,
    event_sequence bigint       NOT NULL,
    from_status    varchar(30)  NULL,
    to_status      varchar(30)  NOT NULL,
    actor          varchar(10)  NOT NULL,
    reason         varchar(500) NULL,
    created_at     datetime(6)  NOT NULL,
    PRIMARY KEY (order_id, event_sequence),
    CONSTRAINT fk_order_event_order FOREIGN KEY (order_id) REFERENCES shop.orders (id),
    CONSTRAINT ck_order_event_actor CHECK (actor IN ('USER','ADMIN','SYSTEM'))
) ENGINE = InnoDB;

-- 결제 결과 -------------------------------------------------
-- 성공한 결제만. 주문당 1행. 시도·재시도는 payment_transactions 에 있다.
CREATE TABLE shop.payments (
    id                   bigint        NOT NULL AUTO_INCREMENT,
    order_id             bigint        NOT NULL,
    provider_payment_key varchar(200)  COLLATE utf8mb4_bin NOT NULL,
    amount               decimal(12,0) NOT NULL,
    status               varchar(10)   NOT NULL,
    approved_at          datetime(6)   NOT NULL,
    refunded_at          datetime(6)   NULL,
    created_at           datetime(6)   NOT NULL,
    updated_at           datetime(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_payment_order (order_id),
    UNIQUE KEY uq_payment_key   (provider_payment_key),
    CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES shop.orders (id),
    CONSTRAINT ck_payment_status      CHECK (status IN ('SUCCEEDED','REFUNDED')),
    CONSTRAINT ck_payment_refunded_at CHECK ((status = 'REFUNDED') = (refunded_at IS NOT NULL)),
    CONSTRAINT ck_payment_amount      CHECK (amount >= 0)
) ENGINE = InnoDB;

-- 결제 거래 -------------------------------------------------
-- CAPTURE 는 시도마다 새 행(새 orderId), REFUND 는 같은 행에서 재시도한다.
-- FAILED 는 결제사 응답·조회로 실패가 확정된 경우에만. 시간 경과만으로 끝내지 않는다.
CREATE TABLE shop.payment_transactions (
    id                   bigint        NOT NULL AUTO_INCREMENT,
    order_id             bigint        NOT NULL,
    transaction_type     varchar(10)   NOT NULL,
    provider_order_id    varchar(64)   COLLATE utf8mb4_bin NULL,
    provider_payment_key varchar(200)  COLLATE utf8mb4_bin NULL,
    amount               decimal(12,0) NOT NULL,
    idempotency_key      varchar(64)   COLLATE utf8mb4_bin NOT NULL,
    status               varchar(20)   NOT NULL,
    attempt_count        int           NOT NULL DEFAULT 0,
    next_retry_at        datetime(6)   NULL,
    lease_token          varchar(64)   NULL,
    lease_expires_at     datetime(6)   NULL,
    last_error_code      varchar(100)  NULL,
    last_error_message   varchar(500)  NULL,
    requested_at         datetime(6)   NULL,
    finished_at          datetime(6)   NULL,
    created_at           datetime(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_payment_tx_provider_order (provider_order_id),
    UNIQUE KEY uq_payment_tx_idempotency    (idempotency_key),
    KEY ix_payment_tx_order      (order_id, created_at),
    KEY ix_payment_tx_next_retry (status, next_retry_at),
    KEY ix_payment_tx_lease      (status, lease_expires_at),
    CONSTRAINT fk_payment_tx_order FOREIGN KEY (order_id) REFERENCES shop.orders (id),
    CONSTRAINT ck_payment_tx_type   CHECK (transaction_type IN ('CAPTURE','REFUND')),
    CONSTRAINT ck_payment_tx_status CHECK (status IN ('PENDING','PROCESSING','RETRY_SCHEDULED','SUCCEEDED','FAILED')),
    CONSTRAINT ck_payment_tx_provider_order CHECK ((transaction_type = 'CAPTURE') = (provider_order_id IS NOT NULL)),
    CONSTRAINT ck_payment_tx_refund_key     CHECK (transaction_type <> 'REFUND' OR provider_payment_key IS NOT NULL),
    CONSTRAINT ck_payment_tx_started_key    CHECK (status IN ('PENDING') OR provider_payment_key IS NOT NULL),
    CONSTRAINT ck_payment_tx_numbers        CHECK (amount >= 0 AND attempt_count >= 0)
) ENGINE = InnoDB;


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
