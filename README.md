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

Until Milestone 5 the checkout URL is a placeholder: no real payment provider is called.

## Run the tests

Requires JDK 21.

```sh
./gradlew test             # unit tests, no Docker needed
./gradlew integrationTest  # repository tests against a real Postgres (Testcontainers, needs Docker)
./gradlew build            # both, plus compilation and packaging (what CI runs)
```
