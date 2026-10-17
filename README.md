# payment-service

A payment backend in Java 21: idempotent order creation, Stripe Checkout (test mode) and
signature-verified webhooks processed exactly once, on PostgreSQL.

## Status

- Done: orders with Idempotency-Key, Stripe Checkout, webhooks (signature check, idempotent
  processing, order state machine, amount-mismatch flagging), 200+ unit and Testcontainers tests in CI.
- Next: fraud rules and rate limiting with Redis, structured JSON logging, full README.

Design notes so far are in [docs/decisions.md](docs/decisions.md).

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
(any future expiry date, any CVC). Stripe then redirects to `GET /orders/{id}`.

### Receiving Stripe webhooks locally

Stripe cannot reach `localhost`, so the [Stripe CLI](https://docs.stripe.com/stripe-cli) relays events
to it. On a Mac with Apple Silicon, without Homebrew:

```sh
mkdir -p ~/.local/stripe-cli && cd ~/.local/stripe-cli
curl -LO https://github.com/stripe/stripe-cli/releases/download/v1.53.1/stripe_1.53.1_mac-os_arm64.tar.gz
tar -xzf stripe_1.53.1_mac-os_arm64.tar.gz && cd -
~/.local/stripe-cli/stripe version
```

Then, from the project folder:

```sh
set -a; source .env; set +a
# Prints the endpoint's signing secret: put it in .env as STRIPE_WEBHOOK_SECRET
~/.local/stripe-cli/stripe listen --api-key "$STRIPE_SECRET_KEY" --print-secret
docker compose up --build -d   # restart the app so it reads the secret
~/.local/stripe-cli/stripe listen --api-key "$STRIPE_SECRET_KEY" --forward-to localhost:8080/webhooks/stripe
```

Leave `stripe listen` running, place an order and pay on the checkout page: the order becomes `PAID`.
In another terminal you can also expire an unpaid checkout or refund a paid one:

```sh
~/.local/stripe-cli/stripe checkout sessions expire <cs_test_... from the database> --api-key "$STRIPE_SECRET_KEY"
~/.local/stripe-cli/stripe refunds create --payment-intent <pi_...> --api-key "$STRIPE_SECRET_KEY"
```

`stripe trigger checkout.session.completed` also works, but its events belong to no order of ours, so they
are stored as ignored.

## Run the tests

Requires JDK 21.

```sh
./gradlew test             # unit tests, no Docker needed
./gradlew integrationTest  # integration tests with Testcontainers (Postgres, stripe-mock); needs Docker
./gradlew build            # both, plus compilation and packaging (what CI runs)
```
