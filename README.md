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
```

## Run the tests

Requires JDK 21.

```sh
./gradlew test
```
