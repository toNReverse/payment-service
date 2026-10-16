# Design decisions

Short notes on choices that are not obvious from the code. Newest at the bottom.

## 1. Javalin and manual dependency injection, no Spring

Javalin is a thin layer over Jetty: routes are plain lambdas or method references, with no annotations
or classpath scanning. All objects are created in `App.main()` and passed through constructors, so the
whole object graph is visible in one method and nothing happens "by magic". The cost is writing the
wiring by hand, which stays manageable at this size.

## 2. Configuration from environment variables, validated at startup

`AppConfig.from(Map)` receives the environment as a map rather than reading `System.getenv()`, so
tests can pass any environment. It collects every problem before throwing, so one failed start lists
all missing variables. `toString()` is overridden because the generated record version would print the
database password (and any password in the Redis URL) into the logs.

## 3. `/health` returns 503 when a dependency is down

`GET /health` reports each dependency and returns 503 if any of them is down. That makes it act as a
readiness check: a load balancer or orchestrator stops sending traffic to an instance that cannot reach
its database. On Kubernetes this would be split into `/health/live` (process is up, never checks
dependencies, otherwise a database outage would restart every pod) and `/health/ready`.

Every check has a bounded timeout (pool connection timeout 2 s, `Connection.isValid` 1 s, Jedis 2 s), so
the endpoint answers even when a dependency hangs.

Since Milestone 3 the service runs its migrations at startup (decision 9), so it needs Postgres to boot
and exits if Postgres is unreachable. Compose starts the app only after Postgres is healthy, and an
orchestrator would restart it. After startup, `/health` reports the database going away. Redis is
connected lazily: the service starts without it and reports it `DOWN`.

## 4. Packaging with `installDist`, not a fat jar

Gradle's built-in `application` plugin produces `build/install/payment-service/` with `lib/*.jar` and a
start script. The Dockerfile copies that directory, so no Shadow plugin is needed and the dependency
jars stay separate.

## 5. Library choices

- **Jackson 3** (`tools.jackson`): Javalin 7 supports it directly, `java.time` types work without an
  extra module, and the JSON log encoder planned for Milestone 8 uses Jackson 3 too, so there is only
  one Jackson on the classpath.
- **Jedis** (`RedisClient`) rather than Lettuce: synchronous and pooled, which is all that counters and
  rate limiting need. Lettuce's async/reactive API would add complexity without benefit here.
- **HikariCP**: the standard JDBC connection pool. jOOQ will use the same `DataSource` from Milestone 3.

## 6. Money is a `long` of minor units plus a `java.util.Currency`

`double` cannot represent 0.10 exactly, so it is never used for money. Between `BigDecimal` and a
`long` of minor units (cents), the `long` wins here: Stripe's API, the database column (`BIGINT`) and
the arithmetic all work in whole cents, so there is never a rounding mode to choose. `BigDecimal`
would be the right tool for division (tax, currency conversion), which this service does not do.

- `java.util.Currency` validates ISO 4217 codes and knows each currency's minor unit (2 for EUR, 0 for
  JPY). Non-monetary codes such as `XAU` (gold) are rejected.
- Codes must be upper case. Stripe sends `"eur"`; the Stripe adapter converts it at the boundary, so
  the domain stays strict.
- Amounts are never negative: a refund is a status change, not a negative amount.
- `Math.addExact`/`multiplyExact` throw on overflow instead of silently wrapping to a negative number.
- `Money` is not `Comparable`: "is 10 EUR more than 10 USD?" has no answer without an exchange rate.
  `isGreaterThan` throws on mixed currencies instead.

## 7. Order status is an enum with an explicit transition table

The allowed transitions live in one `switch` inside `OrderStatus`, and every change goes through
`transitionTo`, which throws `InvalidStatusTransitionException` for anything else. The switch has no
`default`, so adding a status without deciding its transitions does not compile.

The test writes the table out a second time on purpose and checks all 49 (from, to) pairs. If the
test read the production table, a wrong entry would pass. Temporarily allowing `PAID -> PENDING_PAYMENT`
makes the test fail, which confirms this.

## 8. jOOQ classes are generated from the migration files

jOOQ's `DDLDatabase` reads `src/main/resources/db/migration` in Flyway order and generates one class per
table at build time. The generated code therefore always matches the migrations, and compiling needs
no database or Docker, including inside the Docker image build. Generated code lives in `build/` and is
not committed.

The catch: `DDLDatabase` simulates the DDL in an in-memory H2 database, which supports only part of
Postgres's syntax. For example, H2 cannot index a `TEXT` column, so the schema uses `VARCHAR(n)` (in
Postgres both perform the same, and the limits bound what clients can store). If the schema later needs
Postgres-only features (partial indexes, custom types), the alternative is to generate against a real
Postgres started by Testcontainers. Either way, the integration tests run the generated code against a
real Postgres.

## 9. Migrations run when the service starts

`App.main()` runs Flyway before serving any request, so the schema always matches the code that is
running, and there is a single artifact to deploy. Flyway takes a lock in Postgres, so two instances
starting together do not apply the same migration twice. In a larger deployment, migrations would run
as a separate step before the rollout, and every migration would stay compatible with the previous
version of the code (expand, then contract).

## 10. Repositories: interfaces, hand-written mapping, explicit transactions

- `ProductRepository` and `OrderRepository` are interfaces, so HTTP and service tests can use in-memory
  fakes. The jOOQ implementations are tested against Postgres.
- jOOQ records are mapped to domain records by hand. The domain has no jOOQ dependency, and the `Order`
  constructor re-checks its invariants on every loaded row.
- `JooqOrderRepository.insert` writes the order and its items in one transaction. A test inserts an
  item with an unknown product and checks that the order row was rolled back too.
- Items are loaded in product-id order. Line order means nothing for this shop, so there is no
  position column.

## 11. Unit and integration tests are separate Gradle tasks

Tests that need Postgres carry the JUnit tag `integration`. `./gradlew test` skips them and needs no
Docker; `./gradlew integrationTest` runs them; `./gradlew build` (and CI) runs both. All integration
tests share one Testcontainers Postgres (the same `postgres:16-alpine` image as Compose), set up through
the same `Database` class as production. Each test deletes the rows it created, and the seeded
catalog stays.

## 12. Idempotency keys: reserve, call the provider, then save and complete together

`POST /orders` requires an `Idempotency-Key` header. A client that does not know whether its request
arrived (timeout, dropped connection) sends it again with the same key and must get the same order,
never a second one. The key is stored in `idempotency_keys`:

1. **Reserve.** `INSERT ... ON CONFLICT DO NOTHING` creates the row `IN_PROGRESS`. It is a single
   statement, so of several concurrent requests with the same key exactly one inserts; a SELECT followed
   by an INSERT would let two of them through. If the row already exists:
   - a different request fingerprint means the key was reused for another order → **422**;
   - `COMPLETED` → return the order created by the first request, with **201** and
     `Idempotent-Replayed: true` (Stripe's header);
   - `IN_PROGRESS` → the first request is still running → **409** with `Retry-After: 1`.
2. **Do the work while holding the key**: price the items from the catalog and create the Stripe
   checkout session. No database transaction is open during the provider call: holding one (and its
   pooled connection) across a network call of unknown length would starve the pool under load.
3. **Save and complete in one transaction**: insert the order and mark the key `COMPLETED`. There is no
   moment where the order exists but the key does not point to it. That would happen if idempotency
   were a generic HTTP filter storing the response after the handler had already committed the order.
4. **On failure, release the key.** Validation errors and provider outages (502) delete the reservation,
   so the client can retry with the same key. Nothing was saved, and the checkout session (if any) was
   never shown to anyone, so it cannot be paid and simply expires.

**Crashes and fencing.** If the process dies between steps 1 and 3, the key stays `IN_PROGRESS`. After
the lock timeout (60 s, longer than the slowest provider call) a retry takes it over with a
compare-and-set on `locked_at`. That timestamp is also a *fencing token*: completing and releasing only
succeed while `locked_at` is still the caller's. A request that was merely slow, not dead, therefore
cannot also save an order after being taken over: completing fails, its transaction rolls back, and the
key keeps a single order (`OrderIdempotencyIntegrationTest` checks this against Postgres).

**Fingerprint.** SHA-256 of the normalized request: lower-cased email plus lines sorted by product id.
Listing the same products in another order is the same request; changing a quantity is not. Prices are
not part of the request at all; they come from the catalog.

**Replays show the order as it is now.** A replay returns the same order (same id, same checkout page)
in its current state, for example `PAID` once the webhook has arrived, rather than a byte-for-byte copy
of the first response. That keeps idempotency in the service, where it can share the order's
transaction, instead of storing HTTP bodies.

**Limitations.** Keys are global, because there are no client accounts to scope them to, and they never
expire. A production version would scope keys per API client and delete them after 24 hours, as Stripe
does.

## 13. Transactions are bound to the request's thread

The service needs the order insert and the key completion in one transaction without knowing about jOOQ.
`Transactions.inTransaction(work)` runs `work` inside `dsl.transaction(...)`, and the DSLContext is built
with jOOQ's `ThreadLocalTransactionProvider`: while the work runs, every query made through that
DSLContext on the same thread uses the transaction's connection. Repositories therefore need no
transaction parameter. The alternative, passing a transaction object into every repository call, is
more explicit but clutters every signature. The rule to remember is that the work must not hand
database calls to another thread. A Javalin request runs on one thread, so this holds.

## 14. One error format, strict input

Every error is `{"error": "<code>", "details": ["..."]}`, built only in `ApiErrors`: services and the
domain throw, nobody else writes error responses. Unexpected exceptions are logged with their stack trace
and answered with a generic 500, because messages can contain hostnames, SQL or customer data.
Validation reports every problem at once.

JSON input is strict: unknown fields, `2.5` or `"2"` for a quantity are 400s. Jackson 3's defaults would
silently ignore a misspelled field or truncate 2.5 to 2, which for an order means charging for the wrong
thing.

## 15. Stripe Checkout behind the `PaymentGateway` interface

The customer pays on a page hosted by Stripe (Checkout). Card details never reach this service, which
keeps it out of most PCI DSS scope, and whether the payment succeeded arrives later by webhook
(Milestone 6). `OrderService` only sees `PaymentGateway`; `StripePaymentGateway` is the real one.

- **Prices come from our catalog, not from Stripe.** Each order line becomes a Checkout line item with
  inline `price_data` (our stored unit price and product name) rather than a Stripe Product/Price, so
  there is a single catalog and nothing to keep in sync. Stripe adds the lines up itself; a unit test
  checks that the sum equals the order total, and the webhook will compare `amount_total` again.
- **Names are kept on the order lines** (migration V5), like the unit price: the checkout page shows
  "Pandoro 1 kg × 2", and editing the catalog later does not rewrite past orders.
- **The order id travels with the payment**: `client_reference_id` and `metadata.order_id` on the session,
  and `metadata.order_id` on the PaymentIntent, because `payment_intent.*` events do not include the
  session. The webhooks of Milestone 6 use it to find the order.
- **Bounded calls.** The SDK's default read timeout is 80 s. Here it is 5 s to connect and 10 s to read,
  with 2 automatic retries: at most about 50 s, which is less than the 60 s idempotency lock timeout. A
  unit test enforces that relation, because a request still waiting for Stripe must not have its key
  taken over. The SDK resends the same Stripe `Idempotency-Key` (`checkout-session-<order id>`) on
  retries, so a retry never creates a second session.
- **Test mode only.** `AppConfig` refuses any key that is not `sk_test_` or `rk_test_`, without repeating
  the value in the error, and `toString()` shows only the prefix. The key lives in `.env`, which is
  ignored by git and by the Docker build. Without a key the service falls back to `FakePaymentGateway`,
  so CI and `docker compose up` need no Stripe account.
- **Three levels of tests.** The request is built by a pure function and checked without any network
  call. The real SDK then makes real HTTP calls against `stripe-mock` (Stripe's official mock server,
  started by Testcontainers), which rejects any parameter that does not match Stripe's API
  specification; it needs no key, so it runs in CI. Finally a manual run against Stripe's test mode
  confirmed amount, currency, line items and metadata through Stripe's own API.
- **Versions.** stripe-java 34.0.0 pins Stripe API version `2026-09-30.endive`, so upgrading the SDK is
  the only thing that changes the API version.
- **Currencies.** Stripe's "smallest currency unit" matches `Money`'s minor unit for EUR and most
  currencies. A few (such as HUF and ISK) follow Stripe-specific rules; the catalog is in EUR, so they
  are not handled.

## 16. Webhooks: prove it is Stripe, then apply each event exactly once

`POST /webhooks/stripe` is public, so anyone could post a fake "payment succeeded". Every request is
therefore verified first: the `Stripe-Signature` header carries an HMAC-SHA256 of `"<timestamp>.<raw
body>"` keyed with the endpoint's signing secret (`whsec_...`).
- The check runs on the **raw bytes** of the body. Parsing and re-serializing the JSON first would
  change the bytes and break the signature.
- Signatures older than **5 minutes** are refused, so a captured request cannot be replayed later.
- The SDK does the comparison in constant time and accepts several signatures while a secret is rolled.
- Tests sign events with their own HMAC code, so they check Stripe's scheme rather than trust the SDK.

Only the verification uses the SDK. The JSON is read with Jackson, because the SDK's `Event` classes
only deserialize events whose API version matches the SDK's and silently return nothing otherwise. The
parser then turns Stripe events into provider-neutral `PaymentUpdate`s, so the service knows nothing about
Stripe event types.

**Exactly once.** Stripe delivers *at least* once and retries anything that is not a 2xx. Each event is
handled in one transaction:
1. lock the order row (`SELECT ... FOR UPDATE`), so two events for the same order run one after the other;
2. if the event id is already in `payment_events`, it is a redelivery: answer 200, change nothing;
3. decide what the event means (a pure function, unit-tested case by case);
4. insert the event (its id is the primary key) and apply the decision. Both commit or neither does.

Answers: **200** for every genuine event that was dealt with (applied, recorded, ignored or seen before),
**400** for a forged one, **409** for one that came too early (see decision 18), **503** when no signing
secret is configured.

## 17. Mapping Stripe events to order statuses (deviates from the brief)

| Stripe event | Effect |
|---|---|
| `checkout.session.completed` with `payment_status=paid` | `PAID`, after the amount check (decision 19) |
| `checkout.session.completed`, still `unpaid` | recorded only: a delayed method (e.g. SEPA) is still clearing |
| `checkout.session.async_payment_succeeded` | `PAID`, after the amount check |
| `checkout.session.async_payment_failed` | `FAILED` |
| `checkout.session.expired` | `EXPIRED` |
| `payment_intent.payment_failed` | **recorded only, the order stays `PENDING_PAYMENT`** |
| `charge.refunded` | `REFUNDED` if fully refunded; a partial refund is recorded only |
| anything else, or no order id | stored as ignored |

The brief maps `payment_intent.payment_failed` to `FAILED`. With Checkout that is wrong: a declined card
does not end the session, and the customer can try another card on the same page. A terminal `FAILED`
would then block the `PAID` that follows. So a failed attempt is only recorded (with Stripe's reason).
`FAILED` comes from `async_payment_failed`, the only failure that is final, and abandoned checkouts end as
`EXPIRED`.

The order id travels in the metadata (decision 15). A test-mode experiment confirmed that the Charge
inherits the PaymentIntent's metadata, so `charge.refunded` carries the order id as well.

## 18. Events that arrive out of order

Stripe does not guarantee delivery order. When an event's target status cannot be reached directly from
the order's current status, the state machine tells the two cases apart (`OrderStatus.canEventuallyReach`):
- **Reachable later** (a refund while the order is still `PENDING_PAYMENT`): the event came early. Nothing
  is stored and the answer is 409, so Stripe delivers it again later, after the payment event.
- **No longer reachable** (an expiry after `PAID`): the event is stale. It is stored as ignored and
  answered 200.

This works because the state machine has no cycles, so a status that has been passed can never come back.

## 19. Check what Stripe charged before marking an order paid

A payment event marks the order `PAID` only if it is for the order's own checkout session and Stripe's
`amount_total` and currency equal the stored total. Otherwise the order becomes `FLAGGED` with a
`fraud_flags` row saying what differed (`payment_amount_mismatch` or `checkout_session_mismatch`), for a
human to review: money was taken, but not the money we expected.
