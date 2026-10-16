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
