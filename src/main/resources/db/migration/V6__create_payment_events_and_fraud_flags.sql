-- Every verified Stripe webhook, stored once. The primary key on Stripe's event id is what makes webhook
-- processing idempotent: a redelivered event finds its row and is acknowledged without being applied
-- again. The row is written in the same transaction as the order's status change.
--   outcome  APPLIED (status changed), FLAGGED (order flagged), RECORDED (kept, no status change by
--            design, e.g. a failed card attempt), IGNORED (not applicable, e.g. no matching order)
--   payload  the event exactly as verified, for audits and debugging
CREATE TABLE payment_events (
    stripe_event_id VARCHAR(255) PRIMARY KEY,
    event_type      VARCHAR(100) NOT NULL,
    order_id        UUID         REFERENCES orders (id),
    outcome         VARCHAR(20)  NOT NULL CHECK (outcome IN ('APPLIED', 'FLAGGED', 'RECORDED', 'IGNORED')),
    note            VARCHAR(500) NOT NULL,
    payload         JSONB        NOT NULL,
    received_at     TIMESTAMPTZ  NOT NULL
);

CREATE INDEX payment_events_order_id_idx ON payment_events (order_id);

-- Why an order was flagged, for a human to review. Webhooks add one when Stripe charged a different
-- amount; the fraud rules of Milestone 7 add more.
CREATE TABLE fraud_flags (
    id         UUID         PRIMARY KEY,
    order_id   UUID         NOT NULL REFERENCES orders (id),
    rule       VARCHAR(64)  NOT NULL,
    reason     VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL
);

CREATE INDEX fraud_flags_order_id_idx ON fraud_flags (order_id);
