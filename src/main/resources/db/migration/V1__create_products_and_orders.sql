-- Amounts are BIGINT minor units (cents) with a separate ISO 4217 code, like the Money value object.
-- CHECK constraints repeat the domain rules, so bad data is rejected even if it bypasses the application.
-- Strings are VARCHAR(n) rather than TEXT: in Postgres both perform the same, the limits bound what
-- clients can store, and jOOQ's code generator (which simulates this file in H2) cannot index TEXT.

-- The id is a readable slug because it appears in API requests and logs.
CREATE TABLE products (
    id          VARCHAR(64)  PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    price_minor BIGINT       NOT NULL CHECK (price_minor >= 0),
    currency    CHAR(3)      NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE
);

-- Status is a string plus a CHECK rather than a Postgres ENUM: adding a value is then a plain migration.
-- 254 characters is the longest valid email address.
CREATE TABLE orders (
    id                  UUID         PRIMARY KEY,
    customer_email      VARCHAR(254) NOT NULL,
    total_minor         BIGINT       NOT NULL CHECK (total_minor >= 0),
    currency            CHAR(3)      NOT NULL,
    status              VARCHAR(20)  NOT NULL CHECK (status IN (
                            'CREATED', 'PENDING_PAYMENT', 'PAID', 'FAILED', 'EXPIRED', 'REFUNDED', 'FLAGGED')),
    checkout_session_id VARCHAR(255) UNIQUE,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL
);

-- Items use the order's currency. No ON DELETE CASCADE: payment records are never deleted.
CREATE TABLE order_items (
    order_id         UUID        NOT NULL REFERENCES orders (id),
    product_id       VARCHAR(64) NOT NULL REFERENCES products (id),
    quantity         INTEGER     NOT NULL CHECK (quantity > 0),
    unit_price_minor BIGINT      NOT NULL CHECK (unit_price_minor >= 0),
    PRIMARY KEY (order_id, product_id)
);
