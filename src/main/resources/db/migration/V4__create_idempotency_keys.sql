-- One row per Idempotency-Key sent to POST /orders (see docs/decisions.md, "Idempotency keys").
--   request_hash  SHA-256 of the normalized request, to detect a key reused for a different order
--   state         IN_PROGRESS while a request holds the key, COMPLETED once its order is saved
--   locked_at     when the current holder reserved the key; doubles as a fencing token, and lets a later
--                 request take over a reservation whose holder crashed
-- The primary key is what makes the reservation atomic: INSERT ... ON CONFLICT DO NOTHING lets exactly
-- one of several concurrent requests with the same key create the row.
CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    request_hash    CHAR(64)     NOT NULL,
    state           VARCHAR(20)  NOT NULL CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    order_id        UUID         REFERENCES orders (id),
    locked_at       TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    CHECK (
        (state = 'IN_PROGRESS' AND order_id IS NULL)
        OR (state = 'COMPLETED' AND order_id IS NOT NULL)
    )
);
