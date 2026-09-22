-- nova · 스마트폰 사전예약 시스템 스키마
-- 최초 도입 당시 docs/schema.sql의 shop 정의와 설계 주석을 보존한다.
-- 적용한 마이그레이션은 수정하지 않는다. 이후 변경은 새 버전 파일에 작성한다.
--   v13 변경: option_inventories.stock_on_hand → stock_total 개명.
--             실물 재고가 아니라 배정 총량이라 on_hand 가 뜻을 흐렸다. 칼럼 의미·제약은 그대로.
--             ERD 정본만의 정정 — 이 파일에는 원래 없던 것들이다:
--               refresh_tokens.token_hash · product_reviews.order_item_id 의 인라인 UNIQUE 제거
--               (명명 인덱스와 겹쳐 유니크 인덱스가 둘이었다).
--               제거된 ck_preorder_active 를 살아 있는 것처럼 쓴 주석과
--               active_marker 를 직접 대입하라는 주석 삭제.
--   v12 변경: shop.outbox_events 신설(테이블 21개) — v11 의 published_at 칼럼을 표로 승격.
--             preorder_sync_jobs.published_at · ix_sync_job_unpublished 제거(장치를 둘 두지 않는다).
--             preorder_sync_attempts.http_status 추가.
--   v11 변경: (published_at 은 v12 에서 outbox_events 로 대체됨)
--             preorders.active_marker 를 생성 칼럼으로 (ck_preorder_active 제거)
--             preorders.admission_ticket_id + uq_preorder_admission (대기열 입장권 1회권)
--   v10 변경: product_variants → product_options, 재고를 option_inventories 로 분리(테이블 20개).
--   v9  변경: refresh_tokens 신설 + customers.token_version.
--   v8  변경: customers 에 name · email · phone_number.
--   v7 변경: shop.product_reviews 신설(텍스트 + 별점, 사진 없음. 테이블 18개).
--            order_items 에 UNIQUE uq_order_item_product(id, product_id) 추가 — 리뷰의 복합 FK 대상.
--   v6 변경: preorder_sync_jobs 에서 SQS 와 겹치는 칸 제거 —
--            next_retry_at · attempt_count · ix_sync_job_next_retry · ck_sync_job_attempts.
--            payment_transactions 는 같은 칸을 갖고 있으나 토스 경로라 이번 범위가 아니다.
--
-- 전제
--   MySQL 8.0.16 이상. 그 전 버전은 CHECK 를 파싱만 하고 강제하지 않는다.
--   업무 트랜잭션 격리 수준은 READ COMMITTED — 이 파일이 아니라 커넥션 풀에서 설정한다.
--   근거: "없는 행을 잠금 읽기로 확인한 뒤 INSERT" 하는 절차가 REPEATABLE READ 에서
--        갭 락과 삽입 의도 락으로 교착한다(실측 RR 4/4 데드락, RC 4/4 통과).
--   식별자·키 칸은 COLLATE utf8mb4_bin — 대소문자를 구별한다. 기본 콜레이션에 기대지 않는다.
--   부분 인덱스가 없으므로 "조건부 유일" 은 NULL 가능 칸 + UNIQUE 로 표현한다(NULL 끼리는 중복 허용).

SET NAMES utf8mb4;



-- ============================================================
-- shop — 서비스 소유
-- ============================================================

-- 회원 ------------------------------------------------------
CREATE TABLE shop.customers (
    id                          bigint       NOT NULL AUTO_INCREMENT,
    kakao_id                    varchar(64)  COLLATE utf8mb4_bin NOT NULL,
    display_name                varchar(100) NOT NULL,
    -- 카카오 동의 항목. 셋 다 NULL 을 허용한다 — 사용자가 거부하거나 앱 검수를 통과하지 못하면 못 받는다.
    -- "없으면 가입 불가" 로 만들면 동의를 거부한 사용자가 서비스를 못 쓴다.
    -- email 에 UNIQUE 를 걸지 않는다: 카카오 계정 둘이 같은 이메일을 가질 수 있고 두 번째 가입이 막힌다.
    -- 로그인 식별은 kakao_id 가 맡는다.
    name                        varchar(50)  NULL,   -- 실명. 배송지 수령인과 다르다 (수령인은 본인이 아닐 수 있다)
    email                       varchar(255) NULL,
    phone_number                varchar(20)  NULL,   -- 회원 본인 번호. 배송지 연락처는 받는 사람 번호다
    -- 액세스 토큰(JWT)의 tv 클레임과 대조한다. 1 올리면 이미 나가 있는 액세스 토큰이 전부 거절된다.
    -- 액세스 토큰은 무상태라 이것 말고 강제 무효화 수단이 없다.
    token_version               int          NOT NULL DEFAULT 0,
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

-- 리프레시 토큰 --------------------------------------------
-- JWT 로 만들지 않는다. JWT 는 서명만 맞으면 유효해서 폐기할 방법이 없는데,
-- 리프레시는 반드시 폐기할 수 있어야 한다.
-- 원문(256비트 랜덤)은 HttpOnly 쿠키로만 주고 DB 에는 SHA-256 해시만 남긴다.
--
-- 회전: 재발급마다 기존 행에 rotated_at 을 찍고 같은 family_id 로 새 행을 만든다.
--       rotated_at 이 있는 토큰이 다시 오면 탈취로 보고 그 family_id 전체에 revoked_at 을 채운다.
--       family 단위로 끊으므로 다른 기기 로그인은 살아남는다.
--
-- 이 표는 재발급을 막을 뿐 이미 나간 액세스 토큰은 못 막는다 — 그건 customers.token_version 이 한다.
-- 이 ERD 에서 물리 삭제를 허용하는 유일한 테이블이다(이력이 아니라 유효 토큰 목록이라서).
CREATE TABLE shop.refresh_tokens (
    id          bigint       NOT NULL AUTO_INCREMENT,
    customer_id bigint       NOT NULL,
    family_id   char(36)     COLLATE utf8mb4_bin NOT NULL,
    token_hash  binary(32)   NOT NULL,
    expires_at  datetime(6)  NOT NULL,
    rotated_at  datetime(6)  NULL,
    revoked_at  datetime(6)  NULL,
    -- 이상 로그인 확인·기기 목록 표시용. 인증 판정에는 쓰지 않는다.
    client_ip   varchar(45)  NULL,
    user_agent  varchar(255) NULL,
    created_at  datetime(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_refresh_token_hash (token_hash),
    KEY ix_refresh_family   (family_id),
    KEY ix_refresh_customer (customer_id, revoked_at),
    KEY ix_refresh_expires  (expires_at),
    CONSTRAINT fk_refresh_customer FOREIGN KEY (customer_id) REFERENCES shop.customers (id),
    CONSTRAINT ck_refresh_expiry CHECK (expires_at > created_at)
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
-- 재고는 여기 없다 — option_inventories 로 분리했다.
-- 나눈 근거는 잠금 경합이 아니라 실수를 줄이는 것 둘이다.
--   (가) 합쳐 두면 카탈로그가 가격을 저장할 때 JPA 가 재고 칸까지 UPDATE 해 그 사이 확보된 수량이 날아간다.
--   (나) 합쳐 두면 "사전예약 옵션은 재고 0" 이 앱 규칙이 된다 — sale_mode 는 products 에 있어 교차 CHECK 가 불가능하다.
-- 나눈 뒤에도 사전예약 옵션에 재고 행을 만드는 것을 막는 장치는 없다. 바뀐 것은 강제가 아니라 실패 방향이다
-- (합쳤을 때의 실수는 초과 판매, 나눈 뒤의 실수는 "판매 불가").
CREATE TABLE shop.product_options (
    id                 bigint        NOT NULL AUTO_INCREMENT,
    product_id         bigint        NOT NULL,
    sku                varchar(80)   COLLATE utf8mb4_bin NOT NULL,
    title              varchar(120)  NOT NULL,
    price              decimal(12,0) NOT NULL,
    -- 키는 소속 카테고리의 option_filter_definitions 가 정한다. 코드가 고정하지 않는다.
    filter_attributes  json          NULL,
    display_attributes json          NULL,
    status             varchar(20)   NOT NULL,
    created_at         datetime(6)   NOT NULL,
    updated_at         datetime(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_option_sku (product_id, sku),
    -- 예약·주문상품의 모델-옵션 복합 FK 대상
    UNIQUE KEY uq_option_product (product_id, id),
    CONSTRAINT fk_option_product FOREIGN KEY (product_id) REFERENCES shop.products (id),
    CONSTRAINT ck_option_price  CHECK (price >= 0),
    CONSTRAINT ck_option_status CHECK (status IN ('ACTIVE','PAUSED'))
) ENGINE = InnoDB;

-- 재고 --------------------------------------------------------
-- 일반 판매 전용. 사전예약 옵션에는 행을 만들지 않는다(결정 3 — 선점 없음).
-- 행이 없으면 재고 판매 불가로 본다. 일반 판매 옵션 등록과 같은 트랜잭션에서 이 행도 만든다.
--
-- 가용 = stock_total - stock_reserved - stock_sold
-- stock_total 은 창고 실물 수량이 아니라 관리자가 이 옵션에 배정한 총량이다(v13 개명).
-- 모든 변경은 조건부 UPDATE 로 하고 영향 행이 1인지 확인한다.
--
-- 칸이 셋인 이유: 확보(reserved)와 판매(sold)를 합치면 취소 때 무엇을 되돌릴지 알 수 없다.
-- 미결제 취소는 확보를, 결제된 주문의 취소는 판매를 되돌린다.
CREATE TABLE shop.option_inventories (
    option_id      bigint      NOT NULL,
    stock_total    int         NOT NULL DEFAULT 0,
    stock_reserved int         NOT NULL DEFAULT 0,
    stock_sold     int         NOT NULL DEFAULT 0,
    updated_at     datetime(6) NOT NULL,
    PRIMARY KEY (option_id),
    CONSTRAINT fk_inventory_option FOREIGN KEY (option_id) REFERENCES shop.product_options (id),
    CONSTRAINT ck_inventory_nonnegative CHECK (stock_total >= 0 AND stock_reserved >= 0 AND stock_sold >= 0),
    -- 관리자 총량 감소는 확보 + 판매 아래로 불가
    CONSTRAINT ck_inventory_capacity    CHECK (stock_reserved + stock_sold <= stock_total)
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
    option_id  bigint      NOT NULL,
    quantity    int         NOT NULL,
    created_at  datetime(6) NOT NULL,
    updated_at  datetime(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_cart_customer_option (customer_id, option_id),
    KEY ix_cart_option (option_id),
    CONSTRAINT fk_cart_customer FOREIGN KEY (customer_id) REFERENCES shop.customers (id),
    CONSTRAINT fk_cart_option  FOREIGN KEY (option_id)  REFERENCES shop.product_options (id),
    CONSTRAINT ck_cart_quantity CHECK (quantity > 0)
) ENGINE = InnoDB;

-- 예약 ------------------------------------------------------
-- 수량은 항상 1이라 칸을 두지 않는다. 결제 여부는 예약이 아니라 주문·payments 가 주인이다.
CREATE TABLE shop.preorders (
    id                     bigint        NOT NULL AUTO_INCREMENT,
    preorder_token         char(36)      COLLATE utf8mb4_bin NOT NULL,
    customer_id            bigint        NOT NULL,
    product_id             bigint        NOT NULL,
    option_id             bigint        NOT NULL,
    shipment_batch_id      bigint        NOT NULL,
    queue_position         bigint        NOT NULL,
    -- 대기열 입장권(jti). 1회권이라 UNIQUE 로 재사용을 막는다. 관리자 대신 접수는 NULL.
    admission_ticket_id    varchar(64)   COLLATE utf8mb4_bin NULL,
    idempotency_key        varchar(100)  COLLATE utf8mb4_bin NOT NULL,
    product_title_snapshot varchar(100)  NOT NULL,
    option_title_snapshot varchar(120)  NOT NULL,
    unit_price_snapshot    decimal(12,0) NOT NULL,
    status                 varchar(20)   NOT NULL,
    -- 상태에서 DB 가 계산한다(v11). 앱이 쓰려고 하면 MySQL 이 거부한다.
    -- 취소된 예약도 순번을 계속 들고 있어 행을 지울 수 없고, MySQL 에는 부분 인덱스가 없다.
    -- UNIQUE (customer_id, product_id) 로는 재신청이 막히므로 그 우회가 이 칸이다.
    active_marker          tinyint       GENERATED ALWAYS AS
                                           (CASE WHEN status <> 'CANCELED' THEN 1 ELSE NULL END) STORED,
    payable_from           datetime(6)   NULL,
    external_reference     varchar(100)  COLLATE utf8mb4_bin NULL,
    internal_note          text          NULL,
    event_sequence         bigint        NOT NULL DEFAULT 0,
    created_at             datetime(6)   NOT NULL,
    updated_at             datetime(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_preorder_token       (preorder_token),
    UNIQUE KEY uq_preorder_external    (external_reference),
    UNIQUE KEY uq_preorder_admission   (admission_ticket_id),
    UNIQUE KEY uq_preorder_idempotency (customer_id, idempotency_key),
    -- 취소 행 포함 원장 전체
    UNIQUE KEY uq_preorder_position    (product_id, queue_position),
    -- 모델당 1인 1개. NULL(취소 완료)끼리는 중복 허용
    UNIQUE KEY uq_preorder_active      (customer_id, product_id, active_marker),
    -- 주문의 예약-회원 복합 FK 대상
    UNIQUE KEY uq_preorder_id_customer (id, customer_id),
    KEY ix_preorder_payable            (status, payable_from),
    KEY ix_preorder_customer_created   (customer_id, created_at),
    KEY ix_preorder_option            (product_id, option_id),
    KEY ix_preorder_batch              (product_id, shipment_batch_id),
    CONSTRAINT fk_preorder_customer FOREIGN KEY (customer_id) REFERENCES shop.customers (id),
    CONSTRAINT fk_preorder_option  FOREIGN KEY (product_id, option_id)
        REFERENCES shop.product_options (product_id, id),
    CONSTRAINT fk_preorder_batch    FOREIGN KEY (product_id, shipment_batch_id)
        REFERENCES shop.shipment_batches (product_id, id),
    CONSTRAINT ck_preorder_status CHECK (status IN ('PENDING_SYNC','PAYABLE','CANCELING','CANCELED')),
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
--
-- 재시도 스케줄은 여기 없다(v6). SQS 와 나눠 가진 몫:
--   SQS   언제 다시 깨울까(실패 시 ChangeMessageVisibility 로 지수 백오프+지터, 최대 12시간)
--         몇 번 만에 포기할까(maxReceiveCount 초과 → DLQ → DLQ 소비자가 여기에 DEAD_LETTER 를 쓴다)
--   여기  지금 무슨 상태인가 · 누가 쥐고 있나(lease_token) · 예약당 하나인가(uq_sync_job_type)
--         · 취소로 무효화됐나(CANCELED). 이 넷은 SQS 가 대신하지 못한다.
-- 누적 시도 횟수는 preorder_sync_attempts 의 행 수와 항상 같아 칼럼으로 들지 않는다.
-- "재시도 시각이 지난 행" 을 훑는 폴링 배치는 없다.
CREATE TABLE shop.preorder_sync_jobs (
    id               bigint      NOT NULL AUTO_INCREMENT,
    preorder_id      bigint      NOT NULL,
    job_type         varchar(10) NOT NULL,
    -- 접수 때 고정한다. 재시도마다 같은 내용을 보내야 외부가 중복으로 보지 않는다.
    request_payload  json        NOT NULL,
    -- RETRY_SCHEDULED 는 "SQS 가 이 메시지를 숨겨 두고 있다" 는 표시다. 깨울 시각은 SQS 가 들고 있다.
    status           varchar(20) NOT NULL,
    -- 결과 반영은 이 값이 같을 때만. 다르면 아무것도 바꾸지 않는다(리스 만료 후 다른 워커가 판정한 경우).
    -- SQS 는 at-least-once 라 가시성 타임아웃이 살아 있어도 중복 전달이 난다. 그래서 리스는 오히려 더 필요하다.
    lease_token      varchar(64) NULL,
    lease_expires_at datetime(6) NULL,
    dead_lettered_at datetime(6) NULL,
    created_at       datetime(6) NOT NULL,
    updated_at       datetime(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_sync_job_type (preorder_id, job_type),
    KEY ix_sync_job_lease       (status, lease_expires_at),
    CONSTRAINT fk_sync_job_preorder FOREIGN KEY (preorder_id) REFERENCES shop.preorders (id),
    CONSTRAINT ck_sync_job_type   CHECK (job_type IN ('REGISTER','CANCEL')),
    CONSTRAINT ck_sync_job_status CHECK (status IN ('PENDING','PROCESSING','RETRY_SCHEDULED','SUCCEEDED','DEAD_LETTER','CANCELED')),
    CONSTRAINT ck_sync_job_dead_letter              CHECK (status <> 'DEAD_LETTER' OR dead_lettered_at IS NOT NULL),
    CONSTRAINT ck_sync_job_dead_letter_register_only CHECK (job_type = 'REGISTER' OR (status <> 'DEAD_LETTER' AND dead_lettered_at IS NULL)),
    CONSTRAINT ck_sync_job_canceled_register_only    CHECK (job_type = 'REGISTER' OR status <> 'CANCELED')
) ENGINE = InnoDB;

-- 동기화 시도 기록 ------------------------------------------
-- 시도 횟수와 "왜 · 언제 실패했나" 의 유일한 출처다(작업 표에 누적 횟수 칼럼을 두지 않는다).
-- attempt_number 는 그 작업의 직전 최대 번호 + 1. 동시 발급의 최종 방어는 UNIQUE 이고 1062 는 정상 분기다.
-- SQS 의 ApproximateReceiveCount 는 근사값이라 이 번호로 쓰지 않는다.
-- 호출 전에 시작 행을 남긴다. 크래시로 결과를 모르는 시도도 행을 유지한다.
--   result NULL + finished_at NULL  → 프로세스가 죽음. 리스 만료 후 복구 워커가 집는다.
--   result UNKNOWN + finished_at 있음 → 호출은 갔고 응답을 못 받음. 조회로 판단하고 재등록하지 않는다.
CREATE TABLE shop.preorder_sync_attempts (
    id             bigint       NOT NULL AUTO_INCREMENT,
    sync_job_id    bigint       NOT NULL,
    attempt_number int          NOT NULL,
    actor          varchar(10)  NOT NULL,
    result         varchar(20)  NULL,
    -- 응답 자체를 못 받았으면 NULL(타임아웃·연결 끊김). 그때 result 는 UNKNOWN 이다.
    -- errorCode 만으로는 500 과 503 을 못 가른다 — Mock 명세에서 둘 다 UPSTREAM_UNAVAILABLE 이다.
    http_status    smallint     NULL,
    error_code     varchar(100) NULL,
    error_message  varchar(500) NULL,
    started_at     datetime(6)  NOT NULL,
    finished_at    datetime(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_sync_attempt_number (sync_job_id, attempt_number),
    CONSTRAINT fk_sync_attempt_job FOREIGN KEY (sync_job_id) REFERENCES shop.preorder_sync_jobs (id),
    CONSTRAINT ck_sync_attempt_actor  CHECK (actor IN ('SYSTEM','ADMIN')),
    CONSTRAINT ck_sync_attempt_result CHECK (result IS NULL OR result IN ('SUCCESS','TRANSIENT_FAILURE','REJECTED','UNKNOWN')),
    CONSTRAINT ck_sync_attempt_http_status CHECK (http_status IS NULL OR http_status BETWEEN 100 AND 599)
) ENGINE = InnoDB;

-- 트랜잭셔널 아웃박스 ---------------------------------------
-- 업무 변경과 메시지 기록을 한 트랜잭션으로 묶어 "업무는 커밋됐는데 메시지가 큐에 못 들어간" 창을 없앤다.
-- 생산자 셋: 외부 등록·취소 작업 · 알림 · 예약 취소 조정(preorder → order → preorder).
-- 릴레이: batch(1 고정)가 published_at IS NULL 을 id 순으로 발행하고 채운다. 1분 주기, 보통 0행.
-- 소비자는 payload 를 믿지 않고 aggregate_id 로 원장을 다시 읽는다 — 유실·중복·순서가 결과를 바꾸지 않는 이유다.
-- aggregate_type·event_type 에 CHECK 를 두지 않는다: 종류가 늘 때마다 마이그레이션이 붙는다.
-- 행을 지우지 않는다(물리 삭제 예외는 refresh_tokens 하나로 유지).
CREATE TABLE shop.outbox_events (
    id               bigint      NOT NULL AUTO_INCREMENT,
    event_id         char(36)    COLLATE utf8mb4_bin NOT NULL,
    aggregate_type   varchar(30) NOT NULL,
    aggregate_id     bigint      NOT NULL,
    event_type       varchar(50) NOT NULL,
    payload          json        NOT NULL,
    publish_attempts int         NOT NULL DEFAULT 0,
    created_at       datetime(6) NOT NULL,
    published_at     datetime(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_outbox_event_id  (event_id),
    KEY ix_outbox_unpublished (published_at, id),
    CONSTRAINT ck_outbox_attempts CHECK (publish_attempts >= 0)
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
    option_id             bigint        NOT NULL,
    quantity               int           NOT NULL,
    unit_price_snapshot    decimal(12,0) NOT NULL,
    product_title_snapshot varchar(100)  NOT NULL,
    option_title_snapshot varchar(120)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_order_item_option (order_id, option_id),
    -- 리뷰의 주문상품-상품 복합 FK 대상(v7)
    UNIQUE KEY uq_order_item_product (id, product_id),
    KEY ix_order_item_option_ref (product_id, option_id),
    CONSTRAINT fk_order_item_order   FOREIGN KEY (order_id) REFERENCES shop.orders (id),
    CONSTRAINT fk_order_item_option_ref FOREIGN KEY (product_id, option_id)
        REFERENCES shop.product_options (product_id, id),
    CONSTRAINT ck_order_item_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_item_price    CHECK (unit_price_snapshot >= 0)
) ENGINE = InnoDB;

-- 상품 리뷰 -------------------------------------------------
-- 텍스트 + 별점(1~5) 만. 사진 없음.
-- 구매자만 쓴다 — order_item_id 가 구매 증빙이자 중복 방지 키다(주문상품당 1건).
-- 같은 모델을 두 번 사면 주문상품이 둘이므로 리뷰도 둘 쓸 수 있다.
--
-- DB 가 막는 것   산 상품이 아닌 상품에 리뷰 달기 (복합 FK), 주문상품당 2건 (UNIQUE), 별점 범위 (CHECK)
-- 앱 이 막는 것   리뷰어 = 그 주문의 주인 (order_items 에 회원 칸이 없어 orders 를 한 번 더 거친다),
--                 쓸 수 있는 시점(주문이 DELIVERED 인가) — 상태를 넘나드는 조건이라 CHECK 로 표현 불가
--
-- 평균 별점은 여기서 AVG·COUNT 로 계산한다. products 에 캐시 칼럼을 두지 않는다
-- (파생값을 두 군데 들면 어긋난다 — preorder_sync_jobs.attempt_count 를 뺀 것과 같은 이유).
CREATE TABLE shop.product_reviews (
    id                     bigint       NOT NULL AUTO_INCREMENT,
    product_id             bigint       NOT NULL,
    customer_id            bigint       NOT NULL,
    order_item_id          bigint       NOT NULL,
    rating                 tinyint      NOT NULL,
    body                   varchar(2000) NOT NULL,
    -- 어느 옵션을 산 사람의 리뷰인지. 옵션 이름이 바뀌어도 이 값은 그대로다.
    option_title_snapshot varchar(120) NOT NULL,
    created_at             datetime(6)  NOT NULL,
    updated_at             datetime(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_review_order_item (order_item_id),
    KEY ix_review_product  (product_id, created_at),
    KEY ix_review_customer (customer_id, created_at),
    CONSTRAINT fk_review_product  FOREIGN KEY (product_id)  REFERENCES shop.products (id),
    CONSTRAINT fk_review_customer FOREIGN KEY (customer_id) REFERENCES shop.customers (id),
    -- 리뷰의 상품과 주문상품의 상품이 어긋나지 않게 묶는다
    CONSTRAINT fk_review_order_item FOREIGN KEY (order_item_id, product_id)
        REFERENCES shop.order_items (id, product_id),
    CONSTRAINT ck_review_rating CHECK (rating BETWEEN 1 AND 5),
    -- 공백만 넣는 것은 앱에서 막는다. 여기서는 빈 문자열만 거른다.
    CONSTRAINT ck_review_body   CHECK (CHAR_LENGTH(body) > 0)
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
