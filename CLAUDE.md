# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
./gradlew build          # compile + test
./gradlew bootRun        # start application on :8080 (active profile: local)
./gradlew test           # run all tests
./gradlew test --tests "com.bill.circuitBreaker.example.StateTransitionKotlinTest"  # single test class
./gradlew clean build    # clean rebuild
docker-compose up -d     # Prometheus :9090 + Grafana :3000, scraping /actuator/prometheus on :8080
```

## Tech Stack

- **Language:** Kotlin 2.2.20 (plus Java 21 under `src/main/java` / `src/test/java`)
- **Runtime:** Java 21, Spring Boot 4.0.5
- **Resilience:** Resilience4j 2.4.0 (`resilience4j-spring-boot4`, `-reactor`, `-kotlin`)
- **Web:** both `starter-web` and `starter-webflux` are on the classpath, so the app runs as **Spring MVC (servlet)**; Reactor `Mono`/`Flux` are used as return types
- **Metrics:** Micrometer + Prometheus (exposed at `/actuator/prometheus`)
- **Build:** Gradle 9.4.1

## Architecture

This is a demonstration project comparing two approaches to Resilience4j integration:

| Approach | Controller | Service | How |
|---|---|---|---|
| Annotation-based | `BasicController` (`/basic/*`) | `BasicService` | `@CircuitBreaker`, `@Retry`, `@Bulkhead`, `@TimeLimiter`, `@RateLimiter` |
| Functional API | `FunctionalStyleController` (`/functional/*`) | `FunctionalService` | `Decorators.ofSupplier().withCircuitBreaker()…decorate()` + Reactor operators |

Both expose **16 endpoints each** to show both styles side-by-side with identical behaviour. `ResilienceEndpointsTest` runs the same scenarios against both prefixes (path prefix = instance name) — extend it when changing either side.

`example/*` packages contain a separate layer of real-world recipe code: `example/webclient` (Kotlin `WebClient` + Java `RestClient`), `example/coroutine` (Kotlin `suspend fun` with `executeSuspendFunction`), each with its own CB instance (`webClient` / `restClient` / `coroutine`). The WebClient/RestClient examples call this app's own `/basic/*` at `localhost:8080` (`HttpClientsConfig`), so they only work while the app is running.

Tests are all `@SpringBootTest`. Tests under `test/kotlin/example/` and `test/java/example/` share the `test` CB instance (not `basic`/`functional`) and call `reset()` in `@BeforeEach` — keep that when adding tests.

`README.md` and `README.zh-TW.md` mirror each other; update both together.

### Resilience Patterns in Use

All 5 patterns are fully active:
- **Circuit Breaker** — CLOSED → OPEN → HALF_OPEN; 50% failure rate. Window: `basic` 100 (customizer, see Gotchas), `functional` 10 with `minimumNumberOfCalls: 10`, `test` 5 at 60%
- **Retry** — up to 3 attempts, 100ms wait; only on `HttpServerErrorException`, `TimeoutException`, `IOException`
- **Bulkhead** — semaphore (limits concurrent calls) and thread-pool variants
- **Time Limiter** — 2s timeout, used with `CompletableFuture` / `Mono` / `Flux`
- **Rate Limiter** — `basic`: 10/s; `functional`: 6/500ms — both have fallbacks on `RequestNotPermitted`

### Failure Classification

Same outcome, different mechanism — `basic`: `recordExceptions` list in YAML; `functional`: `RecordFailurePredicate`. Keep the two in sync.

- `HttpServerErrorException`, `TimeoutException`, `IOException` — recorded as **failure**
- `HttpClientErrorException` (4xx), `BusinessException` — counted as **success** (still in the sliding window; nothing here uses `ignoreExceptions`, which would skip counting entirely)

### Key Config

- Instance names: `basic` (annotation approach) / `functional` (programmatic approach)
- `ApplicationConfig` registers event consumers logging all circuit breaker state transitions and retry events
- `FunctionalStyleController` resolves all Resilience4j instances from registries **at constructor injection time** (not per-request), then builds decorator chains per endpoint
- `FunctionalService` uses Vavr `Try.ofSupplier()` in `failureWithFallback()` as a non-Resilience4j fallback approach

### Non-obvious Gotchas

- **`testCustomizer()` overrides YAML**: `ApplicationConfig.testCustomizer()` sets `slidingWindowSize = 100` for the `basic` circuit breaker via `CircuitBreakerConfigCustomizer`. The YAML shows `slidingWindowSize: 10` but code-level customizer wins — reading YAML alone is misleading.
- **Annotation AOP order is fixed, not source order**: Resilience4j applies aspects as `Retry(CircuitBreaker(RateLimiter(TimeLimiter(Bulkhead(fn)))))` regardless of how annotations are listed; only `resilience4j.*.*AspectOrder` properties change it (none set here). Consequence: Retry wraps CB, so one `/basic/failure` call records 3 CB failures.
- **Self-invocation**: an annotated `BasicService` method calling another annotated method on `this` bypasses the proxy — throw/call directly instead (see `failureWithFallback()`).
- **Use servlet functional APIs**: the app runs as Spring MVC, so `RouterFunction` beans must come from `org.springframework.web.servlet.function`, not `web.reactive.function` (a reactive one is silently ignored).
- **MockMvc wraps controller exceptions** in `ServletException` — assert with `hasRootCauseInstanceOf`, not `isInstanceOf`.
- **Reactor `transform()` order**: In `FunctionalStyleController.execute(Mono/Flux)`, operators wrap from bottom up — the last `.transform()` call is outermost. So `RetryOperator` is outermost, `BulkheadOperator` is closest to the publisher.
- **Fallback method overloading**: Annotation-based fallbacks in `BasicService` use method overloading (`fallback(ex: HttpServerErrorException)` vs `fallback(ex: Exception)`). Resilience4j picks the most specific matching exception type.

### Endpoints per controller (16 each)

`success`, `failure`, `successException`, `ignore`, `fallback`,
`monoSuccess`, `monoFailure`, `monoTimeout`,
`fluxSuccess`, `fluxFailure`, `fluxTimeout`,
`futureSuccess`, `futureFailure`, `futureTimeout`,
`rateLimited`, `monoRateLimited`
