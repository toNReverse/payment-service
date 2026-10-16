# payment-service

A small payment backend in Java 21: orders, Stripe Checkout (test mode), signature-verified webhooks
and simple fraud rules, on PostgreSQL and Redis.

> Work in progress. The full README (architecture, API examples, design decisions) comes with the last
> milestone. Design notes so far are in [docs/decisions.md](docs/decisions.md).

## Run locally

Requires Docker.

```sh
docker compose up --build
curl -i localhost:8080/health
curl -s localhost:8080/products

# Place an order. Sending the same request again with the same key returns the same order.
curl -i -X POST localhost:8080/orders \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"customerEmail": "anna@example.com", "items": [{"productId": "pandoro", "quantity": 2}]}'

curl -s localhost:8080/orders/<id from the response>
```

### Paying with Stripe (test mode)

Without configuration the service uses a fake payment gateway and the checkout URL is a placeholder.
To get a real Stripe Checkout page, put a **test-mode** secret key in `.env` (never committed; live keys
are refused at startup), then restart:

```sh
cp .env.example .env    # then set STRIPE_SECRET_KEY=sk_test_... in .env
docker compose up --build
```

Open the `checkoutUrl` from the `POST /orders` response and pay with the test card `4242 4242 4242 4242`
(any future expiry date, any CVC). Stripe then redirects to `GET /orders/{id}`. The order stays
`PENDING_PAYMENT` until webhooks are handled (Milestone 6).

## Run the tests

Requires JDK 21.

```sh
./gradlew test             # unit tests, no Docker needed
./gradlew integrationTest  # repository tests against a real Postgres (Testcontainers, needs Docker)
./gradlew build            # both, plus compilation and packaging (what CI runs)
```
