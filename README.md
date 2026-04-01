# Resilience4j Spring Boot Demo

A Spring Boot demo application showcasing all five Resilience4j fault-tolerance patterns using **two parallel implementation styles**:

| Style | Endpoints | How |
|---|---|---|
| **Annotation-based** | `/basic/*` | `@CircuitBreaker`, `@Retry`, `@Bulkhead`, `@TimeLimiter`, `@RateLimiter` |
| **Functional API** | `/functional/*` | `Decorators` builder + Reactor operators (`CircuitBreakerOperator`, etc.) |

Both controllers expose identical endpoints against identical service logic, allowing direct comparison of the two approaches.

---

## Tech Stack

- **Java 21** / **Kotlin 2.2.20**
- **Spring Boot 4.0.5**
- **Gradle 9.4.1**
- **Resilience4j 2.4.0**
- **Project Reactor** (Mono / Flux / CompletableFuture)
- **Micrometer + Prometheus** metrics
- **Spring Actuator** health indicators

---

## Quick Start

```bash
# Build
./gradlew build

# Run application (port 8080)
./gradlew bootRun

# Run tests
./gradlew test

# Monitoring stack (Prometheus + Grafana)
docker-compose up -d
```

- Application: http://localhost:8080
- Actuator: http://localhost:8080/actuator
- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000

---

## Architecture

### Two Implementation Styles

```
com.bill.circuitBreaker/
├── controller/
│   ├── BasicController.kt            # /basic/* — delegates to BasicService
│   └── FunctionalStyleController.kt  # /functional/* — builds decorator chains inline
├── service/
│   ├── Service.kt                    # shared interface
│   └── impl/
│       ├── BasicService.kt           # annotation-based (@CircuitBreaker, @Retry, …)
│       └── FunctionalService.kt      # plain methods — resilience applied in controller
├── config/
│   └── ApplicationConfig.kt          # customizer + registry event consumers
└── exception/
    ├── BusinessException.kt           # exception ignored by circuit breaker
    └── RecordFailurePredicate.kt      # custom failure predicate for 'functional' instance
```

**Annotation style** keeps business logic clean — resilience is declared as metadata. The AOP proxy intercepts calls and applies the chain transparently.

**Functional style** makes the resilience chain explicit and composable. `FunctionalStyleController` builds the chain at runtime using:
- `Decorators.ofSupplier(…).withCircuitBreaker(…).withBulkhead(…).withRetry(…).get()` for synchronous calls
- `.transform(BulkheadOperator.of(bulkhead)).transform(CircuitBreakerOperator.of(cb))` for Mono/Flux
- `Decorators.ofSupplier(…).withThreadPoolBulkhead(…).withTimeLimiter(…)…get().toCompletableFuture()` for async calls

### Instance Configuration

Two named instances — `basic` (used by annotation approach) and `functional` (used by programmatic approach) — are defined in `application.yaml`. The `functional` instance uses a custom `RecordFailurePredicate` that ignores `BusinessException`.

---

## Resilience4j Patterns

### 1. Circuit Breaker

Protects against cascading failures by tracking call outcomes in a sliding window. When the failure rate exceeds the threshold, the circuit **opens** and immediately rejects calls. After a wait duration it transitions to **half-open** to probe recovery.

```
CLOSED ──(failure rate ≥ 50%)──► OPEN ──(wait 5s)──► HALF_OPEN ──(3 probe calls)──► CLOSED/OPEN
```

**Configuration (`basic` instance):**

```yaml
resilience4j.circuitbreaker.instances.basic:
  baseConfig: default          # slidingWindowSize: 10, minimumNumberOfCalls: 5
                               # failureRateThreshold: 50, waitDurationInOpenState: 5s
                               # permittedNumberOfCallsInHalfOpenState: 3
                               # automaticTransitionFromOpenToHalfOpenEnabled: true
```

**Annotation style:**

```kotlin
@CircuitBreaker(name = "basic", fallbackMethod = "fallback")
fun failure(): String {
    throw HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "remote error")
}

private fun fallback(ex: HttpServerErrorException): String = "Recovered: ${ex.message}"
private fun fallback(ex: Exception): String = "Recovered: $ex"  // catch-all overload
```

**Functional style:**

```kotlin
Decorators.ofSupplier { service.failure() }
    .withCircuitBreaker(circuitBreaker)
    .withBulkhead(bulkhead)
    .withRetry(retry)
    .get()
```

**Try it:**

```bash
# Trigger failures to open the circuit
for i in $(seq 1 10); do curl -s http://localhost:8080/basic/failure; echo; done

# Once open, calls are rejected immediately (CallNotPermittedException)
curl http://localhost:8080/basic/failure

# Check state
curl http://localhost:8080/actuator/health | jq '.components.circuitBreakers'
```

---

### 2. Retry

Automatically retries failed calls for transient errors. Configured to retry up to 3 times with a 100ms wait between attempts.

**Configuration:**

```yaml
resilience4j.retry.configs.default:
  maxAttempts: 3
  waitDuration: 100ms
  retryExceptions:
    - org.springframework.web.client.HttpServerErrorException
    - java.util.concurrent.TimeoutException
    - java.io.IOException
```

Only the exceptions listed in `retryExceptions` trigger a retry. `BusinessException` is not listed, so it propagates immediately without retrying.

**Annotation style:**

```kotlin
@CircuitBreaker(name = "basic")
@Retry(name = "basic")
fun failure(): String {
    throw HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "remote error")
    // retried up to 3 times before circuit breaker records the failure
}
```

**Functional style:**

```kotlin
Decorators.ofSupplier(supplier)
    .withCircuitBreaker(circuitBreaker)
    .withBulkhead(bulkhead)
    .withRetry(retry)           // retries wrap the circuit breaker
    .get()
```

**Ordering note:** In the functional API, decoration order matters. Wrapping retry *outside* the circuit breaker means each retry attempt counts as a new call on the circuit breaker's sliding window.

**Try it:**

```bash
curl http://localhost:8080/basic/failure
# Observe 3 retry events in the application log before the final failure
```

---

### 3. Bulkhead

Limits the number of concurrent calls to prevent thread/resource exhaustion. Two variants are demonstrated:

#### Semaphore Bulkhead (synchronous)

Limits concurrent calls using a semaphore. If the limit is reached and `maxWaitDuration` is exceeded, a `BulkheadFullException` is thrown.

```yaml
resilience4j.bulkhead.instances.basic:
  maxConcurrentCalls: 10
resilience4j.bulkhead.instances.functional:
  maxConcurrentCalls: 20
  maxWaitDuration: 10ms
```

```kotlin
@Bulkhead(name = "basic")
fun success(): String = "Hello World"
```

#### Thread Pool Bulkhead (async)

Submits calls to a bounded thread pool. Used with `CompletableFuture` return types. Excess requests are queued or rejected with a `BulkheadFullException`.

```yaml
resilience4j.thread-pool-bulkhead.instances.basic:
  maxThreadPoolSize: 4
  coreThreadPoolSize: 2
  queueCapacity: 2
resilience4j.thread-pool-bulkhead.instances.functional:
  maxThreadPoolSize: 1
  coreThreadPoolSize: 1
  queueCapacity: 1
```

```kotlin
@Bulkhead(name = "basic", type = Bulkhead.Type.THREADPOOL)
@TimeLimiter(name = "basic")
@CircuitBreaker(name = "basic")
fun futureSuccess(): CompletableFuture<String> =
    CompletableFuture.completedFuture("Hello World")
```

**Functional style (thread pool):**

```kotlin
Decorators.ofSupplier(supplier)
    .withThreadPoolBulkhead(threadPoolBulkhead)
    .withTimeLimiter(timeLimiter, scheduledExecutorService)
    .withCircuitBreaker(circuitBreaker)
    .get().toCompletableFuture()
```

**Try it:**

```bash
curl http://localhost:8080/basic/futureSuccess
curl http://localhost:8080/functional/futureSuccess
```

---

### 4. Time Limiter

Enforces a timeout on operations that return `CompletableFuture` or reactive types. If the operation exceeds `timeoutDuration`, a `TimeoutException` is thrown and the circuit breaker records it as a failure.

```yaml
resilience4j.timelimiter.configs.default:
  timeoutDuration: 2s
  cancelRunningFuture: false
```

**Annotation style (Mono):**

```kotlin
@TimeLimiter(name = "basic")
@CircuitBreaker(name = "basic", fallbackMethod = "monoFallback")
fun monoTimeout(): Mono<String> =
    Mono.just("Hello").delayElement(Duration.ofSeconds(10))  // 10s > 2s limit → timeout

private fun monoFallback(ex: Exception): Mono<String> =
    Mono.just("Recovered: $ex")
```

**Functional style (async):**

```kotlin
Decorators.ofSupplier(supplier)
    .withThreadPoolBulkhead(threadPoolBulkhead)
    .withTimeLimiter(timeLimiter, scheduledExecutorService)   // enforces 2s timeout
    .withCircuitBreaker(circuitBreaker)
    .withFallback(listOf(TimeoutException::class.java), ::fallback)
    .get().toCompletableFuture()
```

**Functional style (Mono/Flux):**

```kotlin
publisher
    .transform(TimeLimiterOperator.of(timeLimiter))
    .transform(BulkheadOperator.of(bulkhead))
    .transform(CircuitBreakerOperator.of(circuitBreaker))
    .onErrorResume(TimeoutException::class.java, fallback)
```

**Try it:**

```bash
curl http://localhost:8080/basic/monoTimeout      # triggers timeout fallback
curl http://localhost:8080/basic/futureTimeout    # triggers timeout + specific fallback
curl http://localhost:8080/functional/fluxTimeout
```

---

### 5. Rate Limiter

Controls the call rate by permitting only a fixed number of calls per refresh period. Excess calls wait up to `timeoutDuration` before a `RequestNotPermitted` exception is thrown.

```yaml
resilience4j.ratelimiter.instances.basic:
  limitForPeriod: 10         # 10 calls allowed per period
  limitRefreshPeriod: 1s     # period refreshes every second
  timeoutDuration: 0         # fail immediately if limit exceeded
resilience4j.ratelimiter.instances.functional:
  limitForPeriod: 6
  limitRefreshPeriod: 500ms
  timeoutDuration: 3s        # wait up to 3s for a permit
```

**Annotation style:**

```kotlin
@RateLimiter(name = "basic", fallbackMethod = "rateLimitFallback")
@CircuitBreaker(name = "basic")
fun rateLimitedCall(): String = "Hello World from rate-limited backend basic"

private fun rateLimitFallback(ex: RequestNotPermitted): String =
    "Rate limit exceeded: ${ex.message}"
```

**Functional style (synchronous):**

```kotlin
Decorators.ofSupplier { service.rateLimitedCall() }
    .withRateLimiter(rateLimiter)
    .withCircuitBreaker(circuitBreaker)
    .withBulkhead(bulkhead)
    .withFallback(listOf(RequestNotPermitted::class.java), ::fallback)
    .get()
```

**Functional style (Mono):**

```kotlin
service.monoRateLimited()
    .transform(RateLimiterOperator.of(rateLimiter))
    .transform(CircuitBreakerOperator.of(circuitBreaker))
    .transform(BulkheadOperator.of(bulkhead))
```

**Try it (trigger rate limit):**

```bash
# Fire 12 requests in rapid succession (limit=10/s)
for i in $(seq 1 12); do curl -s http://localhost:8080/basic/rateLimited; echo; done
# First 10: "Hello World from rate-limited backend basic"
# 11th+:    "Rate limit exceeded: RateLimiter 'basic' does not permit further calls"
```

---

### 6. Fallback Strategies

Four distinct fallback approaches are demonstrated:

#### A. Annotation `fallbackMethod` (BasicService)

Resilience4j finds the fallback by matching the exception type with method overloads. More specific types are matched first.

```kotlin
@CircuitBreaker(name = "basic", fallbackMethod = "fallback")
fun failureWithFallback(): String = failure()

// Specific exception — matched first
private fun fallback(ex: HttpServerErrorException): String =
    "Recovered HttpServerErrorException: ${ex.message}"

// Catch-all — matched when no specific overload exists
private fun fallback(ex: Exception): String = "Recovered: $ex"

// For CompletableFuture return type — fallback must also return CompletableFuture
private fun futureFallback(ex: TimeoutException): CompletableFuture<String> =
    CompletableFuture.completedFuture("Recovered TimeoutException: $ex")

private fun futureFallback(ex: BulkheadFullException): CompletableFuture<String> =
    CompletableFuture.completedFuture("Recovered BulkheadFullException: $ex")

private fun futureFallback(ex: CallNotPermittedException): CompletableFuture<String> =
    CompletableFuture.completedFuture("Recovered CallNotPermittedException: $ex")
```

#### B. `Decorators.withFallback` (FunctionalStyleController)

Programmatic fallback for `CompletableFuture` chains.

```kotlin
Decorators.ofSupplier(supplier)
    .withThreadPoolBulkhead(threadPoolBulkhead)
    .withTimeLimiter(timeLimiter, scheduledExecutorService)
    .withCircuitBreaker(circuitBreaker)
    .withFallback(
        listOf(TimeoutException::class.java, CallNotPermittedException::class.java),
        { ex: Throwable -> "Recovered: $ex" }
    )
    .get().toCompletableFuture()
```

#### C. Reactor `onErrorResume` (FunctionalStyleController)

Per-exception fallback chained onto Mono/Flux pipelines.

```kotlin
publisher
    .transform(TimeLimiterOperator.of(timeLimiter))
    .transform(CircuitBreakerOperator.of(circuitBreaker))
    .onErrorResume(TimeoutException::class.java) { ex -> Mono.just("Timeout: $ex") }
    .onErrorResume(CallNotPermittedException::class.java) { ex -> Mono.just("Circuit open: $ex") }
    .onErrorResume(BulkheadFullException::class.java) { ex -> Mono.just("Bulkhead full: $ex") }
```

#### D. Vavr `Try` (FunctionalService)

Pure functional fallback independent of Resilience4j — used in `failureWithFallback()`.

```kotlin
Try.ofSupplier(::failure)
    .recover { ex: Throwable -> fallback(ex) }
    .get()
```

---

## Failure Classification

The circuit breaker differentiates between recorded and ignored exceptions:

| Category | Examples | Effect |
|---|---|---|
| **Recorded** (counts as failure) | `HttpServerErrorException`, `TimeoutException`, `IOException` | Increments failure count in sliding window |
| **Ignored** (transparent) | `HttpClientErrorException` (4xx), `BusinessException` | Passes through without affecting circuit state |
| **Custom predicate** (`functional` instance) | Via `RecordFailurePredicate` | `BusinessException` excluded from recording |

```kotlin
// RecordFailurePredicate.kt — used by the 'functional' circuit breaker instance
class RecordFailurePredicate : Predicate<Throwable> {
    override fun test(t: Throwable): Boolean =
        t !is BusinessException  // ignore BusinessException, record everything else
}
```

---

## Quick Apply Guide

How to add Resilience4j to your own Spring Boot project in minutes.

### 1. Dependencies

```kotlin
// build.gradle.kts
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-aspectj") // required for annotation mode
    implementation("io.github.resilience4j:resilience4j-spring-boot4:2.4.0")
    implementation("io.github.resilience4j:resilience4j-reactor:2.4.0")    // only if using Mono/Flux
}
```

> For Spring Boot 3.x, replace `spring-boot-starter-aspectj` with `spring-boot-starter-aop`
> and use `resilience4j-spring-boot3` instead.

### 2. Minimal YAML Config

Copy this as a starting point and tune the numbers for your use case:

```yaml
resilience4j.circuitbreaker:
  instances:
    myService:
      slidingWindowSize: 10
      minimumNumberOfCalls: 5
      failureRateThreshold: 50
      waitDurationInOpenState: 10s
      permittedNumberOfCallsInHalfOpenState: 3
      automaticTransitionFromOpenToHalfOpenEnabled: true
      registerHealthIndicator: true
      recordExceptions:
        - org.springframework.web.client.HttpServerErrorException
        - java.util.concurrent.TimeoutException
        - java.io.IOException

resilience4j.retry:
  instances:
    myService:
      maxAttempts: 3
      waitDuration: 200ms
      retryExceptions:
        - org.springframework.web.client.HttpServerErrorException
        - java.util.concurrent.TimeoutException
        - java.io.IOException

resilience4j.bulkhead:
  instances:
    myService:
      maxConcurrentCalls: 20
      maxWaitDuration: 10ms

resilience4j.timelimiter:
  instances:
    myService:
      timeoutDuration: 3s

resilience4j.ratelimiter:
  instances:
    myService:
      limitForPeriod: 20
      limitRefreshPeriod: 1s
      timeoutDuration: 0
      registerHealthIndicator: true

# Expose health indicators
management.health.circuitbreakers.enabled: true
management.health.ratelimiters.enabled: true
management.endpoint.health.show-details: always
```

### 3. Annotation Mode — Copy-Paste Starters

All annotations use the same `name` value to look up the YAML config. Mix and match as needed.

**Circuit Breaker with fallback:**

```kotlin
@CircuitBreaker(name = "myService", fallbackMethod = "fallback")
fun callRemoteService(): String {
    // your HTTP call, DB query, etc.
}

// Fallback signature must match the original method's return type
// Add one overload per exception type you want to handle specifically
private fun fallback(ex: HttpServerErrorException): String = "Service unavailable: ${ex.message}"
private fun fallback(ex: Exception): String = "Service unavailable"
```

**Circuit Breaker + Retry + Bulkhead (common combination):**

```kotlin
@CircuitBreaker(name = "myService")
@Retry(name = "myService")
@Bulkhead(name = "myService")
fun callRemoteService(): String { ... }
```

**With timeout (requires CompletableFuture or Mono/Flux return type):**

```kotlin
// CompletableFuture
@Bulkhead(name = "myService", type = Bulkhead.Type.THREADPOOL)
@TimeLimiter(name = "myService")
@CircuitBreaker(name = "myService", fallbackMethod = "fallback")
fun callRemoteService(): CompletableFuture<String> {
    return CompletableFuture.supplyAsync { /* your call */ }
}

private fun fallback(ex: TimeoutException): CompletableFuture<String> =
    CompletableFuture.completedFuture("Timed out")

// Mono (WebFlux)
@TimeLimiter(name = "myService")
@CircuitBreaker(name = "myService", fallbackMethod = "fallback")
fun callRemoteService(): Mono<String> {
    return webClient.get().retrieve().bodyToMono(String::class.java)
}

private fun fallback(ex: Exception): Mono<String> = Mono.just("Timed out")
```

**Rate Limiter:**

```kotlin
@RateLimiter(name = "myService", fallbackMethod = "rateLimitFallback")
fun callRemoteService(): String { ... }

private fun rateLimitFallback(ex: RequestNotPermitted): String = "Too many requests, try again later"
```

### 4. Functional API — Copy-Paste Starters

Use this when you need dynamic decoration (e.g., conditional retry) or want the chain explicit in code.

```kotlin
@Component
class MyServiceClient(
    circuitBreakerRegistry: CircuitBreakerRegistry,
    retryRegistry: RetryRegistry,
    bulkheadRegistry: BulkheadRegistry,
) {
    private val circuitBreaker = circuitBreakerRegistry.circuitBreaker("myService")
    private val retry = retryRegistry.retry("myService")
    private val bulkhead = bulkheadRegistry.bulkhead("myService")

    fun callRemoteService(): String {
        return Decorators.ofSupplier { /* your call */ }
            .withCircuitBreaker(circuitBreaker)
            .withBulkhead(bulkhead)
            .withRetry(retry)
            .withFallback(listOf(Exception::class.java)) { ex -> "Recovered: ${ex.message}" }
            .get()
    }

    // Mono variant
    fun callRemoteServiceReactive(): Mono<String> {
        return Mono.fromSupplier { /* your call */ }
            .transform(BulkheadOperator.of(bulkhead))
            .transform(CircuitBreakerOperator.of(circuitBreaker))
            .transform(RetryOperator.of(retry))
            .onErrorResume(CallNotPermittedException::class.java) { Mono.just("Circuit open") }
    }
}
```

### 5. Annotation Ordering Rules

The annotation execution order is **outermost first** (the annotation listed first in source code is outermost in the AOP proxy stack):

```kotlin
// This order:
@CircuitBreaker(name = "x")   // outermost — executed first
@Retry(name = "x")            // middle
@Bulkhead(name = "x")         // innermost — executed last (closest to actual call)
fun myMethod(): String { ... }
```

Recommended order for most cases:

```
@CircuitBreaker → @Bulkhead → @TimeLimiter → @Retry → @RateLimiter → actual call
```

**Why it matters:**
- `@Retry` inside `@CircuitBreaker` → each retry attempt counts toward the circuit breaker's failure window. One logical operation can trigger multiple failure records.
- `@Retry` outside `@CircuitBreaker` → if the circuit opens mid-retry, the retry itself is interrupted. Cleaner for most cases.

### 6. Fallback Method Rules

| Rule | Example |
|---|---|
| Same return type as original | `fun callX(): String` → `fun fallback(ex: Exception): String` |
| For `Mono`, fallback must return `Mono` | `fun fallback(ex: Exception): Mono<String>` |
| For `CompletableFuture`, fallback must return `CompletableFuture` | `fun fallback(ex: Exception): CompletableFuture<String>` |
| Multiple overloads — more specific type wins | `fallback(ex: TimeoutException)` beats `fallback(ex: Exception)` |
| Must be in the same class | AOP proxy cannot reach a different bean's method |
| Private visibility is fine | `private fun fallback(...)` works for annotation-based fallbacks |

### 7. Common Gotchas

**`@Transactional` + `@CircuitBreaker` on the same method:**
Both use AOP proxies. Place them on separate layers — `@Transactional` on the repository/service, `@CircuitBreaker` on the caller.

**Self-invocation doesn't work:**
Calling an annotated method from within the same class bypasses the AOP proxy. Extract the annotated method to a separate Spring bean.

```kotlin
// BROKEN — self-invocation
class MyService {
    fun doWork() {
        callRemote()  // AOP proxy bypassed, @CircuitBreaker has no effect
    }

    @CircuitBreaker(name = "x")
    fun callRemote(): String { ... }
}

// CORRECT — inject the bean or split into separate components
```

**`@TimeLimiter` only works with async return types:**
`@TimeLimiter` has no effect on methods returning `String`, `void`, etc. It requires `CompletableFuture<T>`, `Mono<T>`, or `Flux<T>`.

**Instance name typo = silent default config:**
If the `name` in the annotation doesn't match any YAML `instances` entry, Resilience4j silently uses the `default` config without any warning. Always verify with `/actuator/health`.

---

## Endpoint Reference

All endpoints respond to `GET`. Both `/basic/*` and `/functional/*` expose the same paths.

| Path (suffix) | Patterns Applied | Description |
|---|---|---|
| `success` | CB + Bulkhead + Retry | Returns success response |
| `failure` | CB + Bulkhead + Retry | Always throws `HttpServerErrorException` |
| `successException` | CB + Bulkhead | Throws `HttpClientErrorException` (4xx — ignored by CB) |
| `ignore` | CB + Bulkhead | Throws `BusinessException` (ignored by CB) |
| `fallback` | CB with fallback | Calls `failure()` then recovers via fallback method |
| `monoSuccess` | CB + Bulkhead + Retry + TimeLimiter | Reactive success (`Mono`) |
| `monoFailure` | CB + Bulkhead + Retry | Reactive failure (`IOException`) |
| `monoTimeout` | CB (fallback) + Bulkhead + TimeLimiter | Reactive timeout (10s > 2s limit) |
| `fluxSuccess` | CB + Retry + TimeLimiter | Flux success (`Hello`, `World`) |
| `fluxFailure` | CB + Bulkhead + Retry | Flux failure (`IOException`) |
| `fluxTimeout` | CB (fallback) + TimeLimiter | Flux timeout |
| `futureSuccess` | CB + Retry + TimeLimiter + ThreadPool Bulkhead | Async success |
| `futureFailure` | CB + Retry + TimeLimiter + ThreadPool Bulkhead | Async failure |
| `futureTimeout` | CB (fallback) + TimeLimiter + ThreadPool Bulkhead | Async timeout |
| `rateLimited` | RateLimiter (10/s) + CB + Bulkhead | Rate-limited sync call |
| `monoRateLimited` | RateLimiter (10/s) + CB + Bulkhead | Rate-limited reactive call |

---

## Configuration Reference

```yaml
resilience4j.circuitbreaker:
  configs:
    default:
      slidingWindowSize: 10            # calls tracked in the sliding window
      minimumNumberOfCalls: 5          # minimum calls before evaluating failure rate
      failureRateThreshold: 50         # % failures to open circuit
      waitDurationInOpenState: 5s      # time to wait before going HALF_OPEN
      permittedNumberOfCallsInHalfOpenState: 3
      automaticTransitionFromOpenToHalfOpenEnabled: true
      recordExceptions:
        - org.springframework.web.client.HttpServerErrorException
        - java.util.concurrent.TimeoutException
        - java.io.IOException

resilience4j.retry:
  configs:
    default:
      maxAttempts: 3
      waitDuration: 100ms

resilience4j.bulkhead:
  instances:
    basic:
      maxConcurrentCalls: 10           # semaphore: max 10 concurrent calls
    functional:
      maxConcurrentCalls: 20
      maxWaitDuration: 10ms            # wait up to 10ms for a permit before rejecting

resilience4j.thread-pool-bulkhead:
  configs:
    default:
      maxThreadPoolSize: 4
      coreThreadPoolSize: 2
      queueCapacity: 2

resilience4j.ratelimiter:
  instances:
    basic:
      limitForPeriod: 10               # 10 permits per refresh period
      limitRefreshPeriod: 1s
      timeoutDuration: 0               # fail immediately if no permit available
    functional:
      limitForPeriod: 6
      limitRefreshPeriod: 500ms
      timeoutDuration: 3s              # wait up to 3s for a permit

resilience4j.timelimiter:
  configs:
    default:
      timeoutDuration: 2s
      cancelRunningFuture: false
```

---

## Monitoring

Start the monitoring stack with Docker:

```bash
docker-compose up -d
```

This starts:
- **Prometheus** at http://localhost:9090 — scrapes `/actuator/prometheus` every 5s
- **Grafana** at http://localhost:3000 — default credentials: `admin` / `admin`

### Key Metrics

```
# Circuit breaker call outcomes
resilience4j_circuitbreaker_calls_seconds_count{kind="successful"}
resilience4j_circuitbreaker_calls_seconds_count{kind="failed"}
resilience4j_circuitbreaker_calls_seconds_count{kind="not_permitted"}

# Circuit breaker state (0=CLOSED, 1=OPEN, 2=HALF_OPEN)
resilience4j_circuitbreaker_state

# Rate limiter
resilience4j_ratelimiter_available_permissions
resilience4j_ratelimiter_waiting_threads

# Retry
resilience4j_retry_calls_total{kind="successful_with_retry"}
resilience4j_retry_calls_total{kind="failed_with_retry"}

# Bulkhead
resilience4j_bulkhead_available_concurrent_calls
resilience4j_bulkhead_max_allowed_concurrent_calls
```

### Actuator Health

```bash
curl http://localhost:8080/actuator/health | jq '.'
```

The health response includes real-time state for each circuit breaker and rate limiter:

```json
{
  "components": {
    "circuitBreakers": {
      "details": {
        "basic":      { "state": "CLOSED", "failureRate": "0.0%", "bufferedCalls": 0 },
        "functional": { "state": "CLOSED", "failureRate": "0.0%", "bufferedCalls": 0 }
      }
    },
    "rateLimiters": {
      "details": {
        "basic":      { "availablePermissions": 10, "numberOfWaitingThreads": 0 },
        "functional": { "availablePermissions": 6,  "numberOfWaitingThreads": 0 }
      }
    }
  }
}
```

Other useful actuator endpoints:

```bash
# All circuit breaker events (state transitions, success/failure calls)
curl http://localhost:8080/actuator/circuitbreakerevents

# Events for a specific instance
curl http://localhost:8080/actuator/circuitbreakerevents/basic

# Retry events
curl http://localhost:8080/actuator/retryevents

# Rate limiter events
curl http://localhost:8080/actuator/ratelimiterevents

# All exposed metrics
curl http://localhost:8080/actuator/metrics
```
