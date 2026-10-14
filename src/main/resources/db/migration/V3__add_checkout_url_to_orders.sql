-- The checkout page URL is stored with the session id, so a retried POST /orders and GET /orders/{id} can
-- return it without asking Stripe again. Stripe's URLs are long, hence 2048.
ALTER TABLE orders ADD COLUMN checkout_url VARCHAR(2048);

-- An order has either both (checkout started) or neither.
ALTER TABLE orders ADD CONSTRAINT orders_checkout_session_complete CHECK (
    (checkout_session_id IS NULL AND checkout_url IS NULL)
    OR (checkout_session_id IS NOT NULL AND checkout_url IS NOT NULL)
);
