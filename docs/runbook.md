# Runbook

Every command needed to run, demo and test the service locally, in the order you usually need them.
All commands run from the project folder:

```sh
cd ~/Desktop/WORK/payment-service
```

## 1. Start and stop

```sh
open -a OrbStack                      # Docker must be running (it does not start on its own after a reboot)
docker compose up --build -d          # app + Postgres + Redis; first build takes a few minutes
docker compose ps                     # the three containers and their health
curl -i localhost:8080/health         # 200 with database and redis UP

docker compose logs -f app            # follow the app logs (use a separate terminal; Ctrl+C to stop following)
docker compose down                   # stop; data stays in the volume
docker compose down -v                # stop and delete the database
```

Configuration lives in `.env` (never committed; see `.env.example`). After changing it, run
`docker compose up -d` again to restart the app with the new values.

## 2. Try the API

```sh
curl -s localhost:8080/products                       # active catalog

KEY=$(uuidgen)                                        # one Idempotency-Key per new order
curl -s -X POST localhost:8080/orders \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  -d '{"customerEmail":"test@example.com","items":[{"productId":"pandoro","quantity":2}]}'

curl -s localhost:8080/orders/ORDER_ID                # replace ORDER_ID with the id from the response
```

Open the `checkoutUrl` from the response and pay with the test card `4242 4242 4242 4242` (any future
date, any CVC). Without the webhook bridge (section 3) the order stays `PENDING_PAYMENT`.

| To show | Do | Expected |
|---|---|---|
| Idempotency | repeat the same `POST` with the same `$KEY` (add `-i`) | same order, header `Idempotent-Replayed: true` |
| Key reuse | same `$KEY`, different body (e.g. `"quantity":5`) | `422 idempotency_key_reused` |
| Validation | no `Idempotency-Key`, or `"quantity":2.5` | `400 validation_failed` with details |
| Fraud rules | new `KEY`, `"quantity":25` | `201` with status `FLAGGED`, no `checkoutUrl` |
| Rate limiting | more than 10 `POST /orders` in one minute | `429 rate_limited` with `Retry-After` |
| Not found | `curl -s localhost:8080/orders/00000000-0000-0000-0000-000000000000` | `404 not_found` |

Thresholds (defaults in `.env.example`): order total 500.00 EUR, 20 per product, 3 orders per email per
hour, 10 orders per IP per minute.

## 3. Stripe webhooks (make an order PAID)

Stripe cannot reach `localhost`, so the Stripe CLI relays its events.

**Install the CLI** (once):

```sh
mkdir -p ~/.local/stripe-cli
curl -L https://github.com/stripe/stripe-cli/releases/download/v1.53.1/stripe_1.53.1_mac-os_arm64.tar.gz | tar -xz -C ~/.local/stripe-cli
~/.local/stripe-cli/stripe version
```

**Start the bridge** in its own terminal and leave it running:

```sh
set -a; source .env; set +a
~/.local/stripe-cli/stripe listen --api-key "$STRIPE_SECRET_KEY" \
  --events checkout.session.completed,checkout.session.async_payment_succeeded,checkout.session.async_payment_failed,checkout.session.expired,payment_intent.payment_failed,charge.refunded \
  --forward-to localhost:8080/webhooks/stripe
```

It prints `Your webhook signing secret is whsec_...`. That value must equal `STRIPE_WEBHOOK_SECRET` in
`.env`; if it does not, update `.env` and run `docker compose up -d`. Then place a **new** order and pay:
the bridge shows `checkout.session.completed` and `<-- [200]`, and the order becomes `PAID`. A payment
made while the bridge was off is never delivered.

**More to show** (in a terminal where you ran `set -a; source .env; set +a`):

```sh
S=~/.local/stripe-cli/stripe

# Redeliver an event: the service answers DUPLICATE and changes nothing
$S events resend EVENT_ID --api-key "$STRIPE_SECRET_KEY"

# Expire an unpaid checkout: the order becomes EXPIRED (session id is in the database, section 4)
$S checkout sessions expire CHECKOUT_SESSION_ID --api-key "$STRIPE_SECRET_KEY"

# Refund a paid order: the order becomes REFUNDED
$S checkout sessions retrieve CHECKOUT_SESSION_ID --api-key "$STRIPE_SECRET_KEY" | grep '"payment_intent"'
$S refunds create --payment-intent PAYMENT_INTENT_ID --api-key "$STRIPE_SECRET_KEY"
```

## 4. Look inside

```sh
db() { docker compose exec -T postgres psql -U payments -d payments -c "$1"; }   # define once per terminal

db "select id, status, total_minor, checkout_session_id from orders order by created_at desc limit 5;"
db "select event_type, outcome, note from payment_events order by received_at;"
db "select order_id, rule, reason from fraud_flags;"
db "select idempotency_key, state, order_id from idempotency_keys;"

docker compose exec redis redis-cli --scan            # rate-limit and orders-per-email counters
```

If a `psql` result ever stops at `--More--` (when run without `-T`), Space scrolls and `q` quits.

## 5. Tests

```sh
export JAVA_HOME="$HOME/.local/jdk-21/Contents/Home"
./gradlew test                 # unit tests, no Docker needed
./gradlew integrationTest      # Testcontainers: real Postgres, Redis and stripe-mock (Docker must be running)
./gradlew build                # both, as CI does
open build/reports/tests/test/index.html
```

## 6. When something goes wrong

| Symptom | Cause and fix |
|---|---|
| `Cannot connect to the Docker daemon` / integration tests fail with "valid Docker environment" | OrbStack is not running: `open -a OrbStack` |
| Commands you type are echoed but nothing happens | the terminal is still in `docker compose logs -f`; press `Ctrl+C` |
| `zsh: parse error near \n` | a placeholder such as `ORDER_ID` or `<...>` was left in the command |
| `stripe listen` says `must specify events` | add the `--events ...` list from section 3 |
| Order stays `PENDING_PAYMENT` after paying | the bridge was not running, or its `whsec_` differs from `.env` |
| Bridge shows `[400]` | signature rejected: `STRIPE_WEBHOOK_SECRET` in `.env` does not match the bridge |
| `POST /webhooks/stripe` answers `503` | `STRIPE_WEBHOOK_SECRET` is not set; set it and run `docker compose up -d` |
| `429` on every order | rate limit reached; wait for the next minute (see `Retry-After`) |
