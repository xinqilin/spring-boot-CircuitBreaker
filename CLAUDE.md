# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
./gradlew build          # compile + test
./gradlew bootRun        # start application (active profile: local)
./gradlew test           # run all tests
./gradlew test --tests "com.bill.circuitBreaker.SomeTest"  # single test class
./gradlew clean build    # clean rebuild
```

## Tech Stack

- **Language:** Kotlin 2.2.20
- **Runtime:** Java 21, Spring Boot 4.0.5
- **Resilience:** Resilience4j 2.4.0 (`resilience4j-spring-boot4`)
- **Reactive:** Spring WebFlux + Project Reactor (`Mono`, `Flux`)
- **Metrics:** Micrometer + Prometheus (exposed at `/actuator/prometheus`)
- **Build:** Gradle 9.4.1

## Architecture

This is a demonstration project comparing two approaches to Resilience4j integration:

| Approach | Controller | Service | How |
|---|---|---|---|
| Annotation-based | `BasicController` (`/basic/*`) | `BasicService` | `@CircuitBreaker`, `@Retry`, `@Bulkhead`, `@TimeLimiter`, `@RateLimiter` |
| Functional API | `FunctionalStyleController` (`/functional/*`) | `FunctionalService` | `Decorators.ofSupplier().withCircuitBreaker()…decorate()` + Reactor operators |

Both expose **16 endpoints each** — the intent is to show both styles side-by-side with identical behaviour.

### Resilience Patterns in Use

All 5 patterns are fully active:
- **Circuit Breaker** — CLOSED → OPEN → HALF_OPEN state machine; 50% failure rate over a sliding window of 10
- **Retry** — up to 3 attempts, 100ms wait; only on `HttpServerErrorException`, `TimeoutException`, `IOException`
- **Bulkhead** — semaphore (limits concurrent calls) and thread-pool variants
- **Time Limiter** — 2s timeout, used with `CompletableFuture` / `Mono` / `Flux`
- **Rate Limiter** — `basic`: 10/s; `functional`: 6/500ms — both have fallbacks on `RequestNotPermitted`

### Failure Classification

- `BusinessException` — **ignored** by circuit breaker (`functional` instance uses `RecordFailurePredicate`)
- `HttpServerErrorException`, `TimeoutException`, `IOException` — **recorded** as failures
- `HttpClientErrorException` (4xx) — **ignored** (passes through transparently)

### Key Config

- Instance names: `basic` (annotation approach) / `functional` (programmatic approach)
- `ApplicationConfig` registers event consumers logging all circuit breaker state transitions and retry events
- `FunctionalStyleController` manually injects all registry beans and builds decorator chains; also declares `RateLimiterOperator` for reactive pipelines
- `FunctionalService` uses Vavr `Try.ofSupplier()` in `failureWithFallback()` as a non-Resilience4j fallback approach

### Endpoints per controller (16 each)

`success`, `failure`, `successException`, `ignore`, `fallback`,
`monoSuccess`, `monoFailure`, `monoTimeout`,
`fluxSuccess`, `fluxFailure`, `fluxTimeout`,
`futureSuccess`, `futureFailure`, `futureTimeout`,
`rateLimited`, `monoRateLimited`
