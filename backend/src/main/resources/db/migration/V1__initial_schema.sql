-- Flash Sale schema v1.
-- Money is NUMERIC(19,2) → Java BigDecimal. Never double/float.

CREATE TABLE product (
    id                BIGSERIAL PRIMARY KEY,
    name              VARCHAR(255) NOT NULL,
    price             NUMERIC(19, 2) NOT NULL,
    total_stock       INTEGER      NOT NULL,
    available_stock   INTEGER      NOT NULL,
    drop_starts_at    TIMESTAMPTZ  NOT NULL,
    status            VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    version           BIGINT       NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT product_available_stock_nonneg CHECK (available_stock >= 0),
    CONSTRAINT product_total_stock_nonneg     CHECK (total_stock >= 0),
    CONSTRAINT product_status_check           CHECK (status IN ('ACTIVE','SOLD_OUT','DRAFT','ARCHIVED'))
);

CREATE TABLE orders (
    id                       BIGSERIAL PRIMARY KEY,
    product_id               BIGINT       NOT NULL REFERENCES product(id),
    quantity                 INTEGER      NOT NULL,
    buyer_name               VARCHAR(255) NOT NULL,
    buyer_email              VARCHAR(255) NOT NULL,
    buyer_phone              VARCHAR(64)  NOT NULL,
    shipping_address         TEXT         NOT NULL,
    amount                   NUMERIC(19, 2) NOT NULL,
    status                   VARCHAR(32)  NOT NULL DEFAULT 'CREATED',
    payment_idempotency_key  VARCHAR(64)  NOT NULL UNIQUE,
    provider                 VARCHAR(32)  NOT NULL DEFAULT 'PAYUNI',
    provider_ref             VARCHAR(255),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at               TIMESTAMPTZ  NOT NULL,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT orders_quantity_positive CHECK (quantity > 0),
    CONSTRAINT orders_status_check
        CHECK (status IN ('CREATED','PAID','SHIPPED','COMPLETED','EXPIRED','CANCELLED'))
);

CREATE INDEX idx_orders_status_expires_at ON orders (status, expires_at);
CREATE INDEX idx_orders_product           ON orders (product_id);

CREATE TABLE payment_event (
    id               BIGSERIAL PRIMARY KEY,
    order_id         BIGINT       NOT NULL REFERENCES orders(id),
    provider         VARCHAR(32)  NOT NULL,
    provider_txn_id  VARCHAR(255) NOT NULL,
    raw_payload      TEXT         NOT NULL,
    received_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed        BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT payment_event_provider_txn_unique UNIQUE (provider, provider_txn_id)
);

CREATE INDEX idx_payment_event_order ON payment_event (order_id);
