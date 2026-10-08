# Resilience4j Patterns in Depth

[Back to README](../README.md)

## 1. Circuit Breaker

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

## 2. Retry

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

## 3. Bulkhead

Limits the number of concurrent calls to prevent thread/resource exhaustion. Two variants are demonstrated:

### Semaphore Bulkhead (synchronous)

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

### Thread Pool Bulkhead (async)

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

## 4. Time Limiter

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

## 5. Rate Limiter

Controls the call rate by permitting only a fixed number of calls per refresh period. Excess calls wait up to `timeoutDuration` before a `RequestNotPermitted` exception is thrown. Both instances use `timeoutDuration: 0` (reject at once): in a servlet app a positive value makes excess callers hold a request thread while they wait.

```yaml
resilience4j.ratelimiter.instances.basic:
  limitForPeriod: 10         # 10 calls allowed per period
  limitRefreshPeriod: 1s     # period refreshes every second
  timeoutDuration: 0         # fail immediately if limit exceeded
resilience4j.ratelimiter.instances.functional:
  limitForPeriod: 6
  limitRefreshPeriod: 500ms
  timeoutDuration: 0         # also rejects at once; only the quota differs
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
    .withFallback(listOf(RequestNotPermitted::class.java), ::rateLimitFallback)
    .get()
```

**Functional style (Mono):**

```kotlin
service.monoRateLimited()
    .transform(RateLimiterOperator.of(rateLimiter))
    .transform(CircuitBreakerOperator.of(circuitBreaker))
    .transform(BulkheadOperator.of(bulkhead))
    .onErrorResume(RequestNotPermitted::class.java) { ex -> Mono.just(rateLimitFallback(ex)) }
```

**Try it (trigger rate limit):**

```bash
# Fire 12 requests in rapid succession (limit=10/s)
for i in $(seq 1 12); do curl -s http://localhost:8080/basic/rateLimited; echo; done
# First 10: "Hello World from rate-limited backend basic"
# 11th+:    "Rate limit exceeded: RateLimiter 'basic' does not permit further calls"
```

---

## 6. Fallback Strategies

Four distinct fallback approaches are demonstrated:

### A. Annotation `fallbackMethod` (BasicService)

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

### B. `Decorators.withFallback` (FunctionalStyleController)

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

### C. Reactor `onErrorResume` (FunctionalStyleController)

Per-exception fallback chained onto Mono/Flux pipelines.

```kotlin
publisher
    .transform(TimeLimiterOperator.of(timeLimiter))
    .transform(CircuitBreakerOperator.of(circuitBreaker))
    .onErrorResume(TimeoutException::class.java) { ex -> Mono.just("Timeout: $ex") }
    .onErrorResume(CallNotPermittedException::class.java) { ex -> Mono.just("Circuit open: $ex") }
    .onErrorResume(BulkheadFullException::class.java) { ex -> Mono.just("Bulkhead full: $ex") }
```

### D. Vavr `Try` (FunctionalService)

Pure functional fallback independent of Resilience4j — used in `failureWithFallback()`.

```kotlin
Try.ofSupplier(::failure)
    .recover { ex: Throwable -> fallback(ex) }
    .get()
```

---

## Failure Classification

Both instances classify exceptions the same way, by different mechanisms (`basic`: `recordExceptions` list; `functional`: `RecordFailurePredicate`):

| Category | Examples | Effect |
|---|---|---|
| **Recorded** (counts as failure) | `HttpServerErrorException`, `TimeoutException`, `IOException` | Increments failure count in sliding window |
| **Not recorded** (counts as success) | `HttpClientErrorException` (4xx), `BusinessException` | Exception still propagates to the caller; the call enters the sliding window as a success |

Note the difference from `ignoreExceptions`: an *ignored* exception is not counted at all, while an exception that is merely not recorded is counted as a success. Neither instance uses `ignoreExceptions`.

```kotlin
// RecordFailurePredicate.kt — used by the 'functional' circuit breaker instance
class RecordFailurePredicate : Predicate<Throwable> {
    override fun test(t: Throwable): Boolean =
        // Allow-list: a deny-list would also record Resilience4j's own RequestNotPermitted
        t is HttpServerErrorException || t is TimeoutException || t is IOException
}
```
