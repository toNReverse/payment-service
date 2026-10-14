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
