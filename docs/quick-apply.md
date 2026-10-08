# Quick Apply Guide

[Back to README](../README.md)

How to add Resilience4j to your own Spring Boot project in minutes.

## 1. Dependencies

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

## 2. Minimal YAML Config

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

## 3. Annotation Mode — Copy-Paste Starters

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

## 4. Functional API — Copy-Paste Starters

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

## 5. Annotation Ordering Rules

The order in which annotations are **written in source code does not matter**. Resilience4j's Spring aspects always apply in a fixed order (outermost first):

```
Retry ( CircuitBreaker ( RateLimiter ( TimeLimiter ( Bulkhead ( actual call ) ) ) ) )
```

To change it, set aspect-order properties (higher value = higher priority = applied further out), or use the functional `Decorators` / Reactor operator style instead:

```yaml
resilience4j:
  retry:
    retryAspectOrder: 2
  circuitbreaker:
    circuitBreakerAspectOrder: 1
```

**Why it matters:**
- With the default order, `@Retry` wraps `@CircuitBreaker` → every retry attempt is recorded in the circuit breaker's sliding window. One logical call to `/basic/failure` (3 attempts) records 3 failures.
- Once the circuit opens, the next attempt fails fast with `CallNotPermittedException`; it is not in `retryExceptions`, so retry stops there.

## 6. Fallback Method Rules

| Rule | Example |
|---|---|
| Same return type as original | `fun callX(): String` → `fun fallback(ex: Exception): String` |
| For `Mono`, fallback must return `Mono` | `fun fallback(ex: Exception): Mono<String>` |
| For `CompletableFuture`, fallback must return `CompletableFuture` | `fun fallback(ex: Exception): CompletableFuture<String>` |
| Multiple overloads — more specific type wins | `fallback(ex: TimeoutException)` beats `fallback(ex: Exception)` |
| Must be in the same class | AOP proxy cannot reach a different bean's method |
| Private visibility is fine | `private fun fallback(...)` works for annotation-based fallbacks |

## 7. Common Gotchas

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

## 8. WebClient / RestClient Integration

The most common real-world pattern: wrap an HTTP downstream call with a circuit breaker.

**Key difference**: `WebClient` throws `WebClientResponseException` for error status codes (not `HttpServerErrorException`), so you must map 5xx to a recorded exception with `.onStatus()`. `RestClient` throws `HttpServerErrorException` by default — no mapping needed.

**WebClient — annotation style (Kotlin)**

```kotlin
// application.yaml: resilience4j.circuitbreaker.instances.downstream.baseConfig: default
@Component
class DownstreamService(private val webClient: WebClient, ...) {

    @CircuitBreaker(name = "downstream", fallbackMethod = "fallback")
    @TimeLimiter(name = "downstream")
    fun fetchData(): Mono<String> = webClient.get()
        .uri("/api/data")
        .retrieve()
        .onStatus(HttpStatusCode::is5xxServerError) {
            // Map WebClientResponseException → IOException (which is in recordExceptions)
            Mono.error(IOException("Downstream 5xx"))
        }
        .bodyToMono(String::class.java)

    private fun fallback(ex: Exception): Mono<String> =
        Mono.just("fallback: ${ex.javaClass.simpleName}")
}
```

**WebClient — functional style (Reactor operators)**

```kotlin
fun fetchDataFunctional(): Mono<String> = webClient.get()
    .uri("/api/data")
    .retrieve()
    .onStatus(HttpStatusCode::is5xxServerError) { Mono.error(IOException("5xx")) }
    .bodyToMono(String::class.java)
    .transform(CircuitBreakerOperator.of(circuitBreaker))  // CB wraps retry
    .transform(RetryOperator.of(retry))
    .onErrorReturn(CallNotPermittedException::class.java, "circuit open — fallback")
```

**RestClient — annotation style (Java 21)**

```java
// RestClient throws HttpServerErrorException for 5xx by default — matches recordExceptions.
@CircuitBreaker(name = "downstream", fallbackMethod = "fallback")
public DownstreamResponse fetchData() {
    var body = restClient.get()
        .uri("/api/data")
        .retrieve()
        .body(String.class);             // throws HttpServerErrorException on 5xx
    return new DownstreamResponse(body, "live");
}

// Java 21 pattern-matched switch in fallback
private DownstreamResponse fallback(Throwable ex) {
    var message = switch (ex) {
        case HttpServerErrorException e -> "5xx: " + e.getStatusCode();
        case CallNotPermittedException e -> "circuit open";
        default -> "fallback: " + ex.getMessage();
    };
    return new DownstreamResponse(message, "fallback");
}
```

**Shared bean (recommended)**

Define `WebClient` and `RestClient` as `@Bean` singletons — they are thread-safe and pool connections:

```kotlin
@Configuration
class HttpClientsConfig {
    @Bean fun webClient(): WebClient = WebClient.builder().baseUrl(baseUrl).build()
    @Bean fun restClient(): RestClient = RestClient.builder().baseUrl(baseUrl).build()
}
```

**Verification**

```bash
curl localhost:8080/example/webclient/success    # happy path via WebClient
curl localhost:8080/example/webclient/failure    # triggers CB failure counter
curl localhost:8080/example/restclient/success   # happy path via RestClient (Java)
curl localhost:8080/example/restclient/failure   # triggers CB failure counter
```

## 9. Coroutine Integration

Resilience4j provides `executeSuspendFunction` Kotlin extension that wraps a `suspend` lambda without blocking the coroutine dispatcher.

**Dependency** (required alongside `resilience4j-all`):

```kotlin
implementation("io.github.resilience4j:resilience4j-kotlin:2.4.0")
```

**Circuit breaker wrapping a suspend lambda**

```kotlin
@Component
class MyService(circuitBreakerRegistry: CircuitBreakerRegistry) {
    private val cb = circuitBreakerRegistry.circuitBreaker("myService")

    suspend fun fetchData(): String = cb.executeSuspendFunction {
        // Any suspend body — no blocking, no Mono wrapping needed
        externalApi.getData()
    }
}
```

**Combining CB + Retry**

```kotlin
suspend fun fetchWithRetry(): String = retry.executeSuspendFunction {
    cb.executeSuspendFunction {
        externalApi.getData()
    }
}
// Retry is the outer decorator: each retry attempt re-enters the CB check.
```

**Suspend controller (Spring MVC / WebFlux both supported)**

```kotlin
@RestController
class MyController(private val myService: MyService) {

    @GetMapping("/data")
    suspend fun data(): String = myService.fetchData()
    // Spring detects suspend and wraps via kotlinx-coroutines-reactor adapter.
    // No Mono/CompletableFuture wrapper needed.
}
```

**Fallback in coroutine context**

```kotlin
suspend fun fetchSafe(): String = try {
    cb.executeSuspendFunction { externalApi.getData() }
} catch (ex: CallNotPermittedException) {
    "circuit open — cached value"
} catch (ex: IOException) {
    "downstream unavailable"
}
```

> **Pitfall**: Do NOT call `Mono.block()` inside a `suspend` function — it pins and blocks the coroutine dispatcher thread, defeating the purpose of coroutines.

**Verification**

```bash
curl localhost:8080/example/coroutine/success   # suspend fun happy path
curl localhost:8080/example/coroutine/failure   # drives CB failure counter
curl localhost:8080/example/coroutine/retry     # retry exhausted after failures
```

## 10. State-Transition Testing

Testing circuit breaker behavior requires either forcing state directly or driving it with controlled failures.

**Test instance config** — a dedicated YAML instance for tests prevents polluting production metrics and ensures determinism:

```yaml
resilience4j.circuitbreaker.instances:
  test:
    slidingWindowSize: 5
    minimumNumberOfCalls: 5
    failureRateThreshold: 60
    waitDurationInOpenState: 10s
    automaticTransitionFromOpenToHalfOpenEnabled: false  # prevents async state changes during assertions
    permittedNumberOfCallsInHalfOpenState: 2
```

**Forced transition (fastest — for testing fallback in isolation)**

```kotlin
@SpringBootTest
class CircuitBreakerTest {
    @Autowired lateinit var registry: CircuitBreakerRegistry
    private lateinit var cb: CircuitBreaker

    @BeforeEach fun setUp() {
        cb = registry.circuitBreaker("test")
        cb.reset()  // CLOSED + clear sliding window
    }

    @Test fun `forced open rejects calls`() {
        cb.transitionToOpenState()
        assertThat(cb.state).isEqualTo(CircuitBreaker.State.OPEN)
        assertThatThrownBy { CircuitBreaker.decorateSupplier(cb) { "x" }.get() }
            .isInstanceOf(CallNotPermittedException::class.java)
    }
}
```

**Burst-fail (drives CLOSED → OPEN naturally)**

```kotlin
@Test fun `burst failures drive OPEN`() {
    repeat(5) {
        runCatching { cb.executeCheckedSupplier { throw IOException("fail") } }
    }
    // 5 failures / 5 calls = 100% > 60% threshold → OPEN
    assertThat(cb.state).isEqualTo(CircuitBreaker.State.OPEN)
}
```

**Java 21 equivalent — pattern-matched switch on state**

```java
@Test
void forcedTransition() {
    cb.transitionToOpenState();
    var description = switch (cb.getState()) {
        case OPEN     -> "circuit open — calls rejected";
        case CLOSED   -> "circuit closed — calls allowed";
        case HALF_OPEN -> "circuit half-open — limited calls";
        default       -> "other: " + cb.getState();
    };
    assertThat(description).startsWith("circuit open");
}
```

**StepVerifier for reactive pipelines**

```kotlin
@Test fun `open CB emits CallNotPermittedException on Mono`() {
    cb.transitionToOpenState()
    StepVerifier.create(
        Mono.just("data").transform(CircuitBreakerOperator.of(cb))
    )
        .expectError(CallNotPermittedException::class.java)
        .verify()
}

@Test fun `fallback value emitted via onErrorReturn`() {
    cb.transitionToOpenState()
    StepVerifier.create(
        Mono.just("data")
            .transform(CircuitBreakerOperator.of(cb))
            .onErrorReturn(CallNotPermittedException::class.java, "fallback")
    )
        .expectNext("fallback")
        .verifyComplete()
}
```

## 11. Operational Guidance

### Decision Matrix

**Annotation vs Functional API**

| Criterion | Annotation | Functional |
|---|---|---|
| Your code owns the method | Preferred | Optional |
| Third-party library call | Not possible | Use `Decorators.ofSupplier` |
| Reactive chain (Mono/Flux) | Works via `@TimeLimiter` | `transform()` — more explicit |
| Kotlin coroutines | Not supported | `executeSuspendFunction` |
| Multiple patterns stacked | Yes (with ordering caution) | Yes (explicit chain) |
| Visibility into decorator order | Implicit (AOP) | Explicit (code order) |

**Semaphore vs Thread Pool Bulkhead**

| Criterion | Semaphore (`SEMAPHORE`) | Thread Pool (`THREADPOOL`) |
|---|---|---|
| Return type | `String`, `Mono<T>`, `Flux<T>` | `CompletableFuture<T>` only |
| Resource cost | Low (counter only) | High (dedicated thread pool) |
| Caller thread | Caller executes the method | Offloaded to pool thread |
| Timeout support | No | Yes (via `@TimeLimiter`) |
| Use case | Limit concurrency on fast calls | Isolate slow / blocking calls |

### Production Tuning Cheatsheet

| Parameter | Low traffic (≤100 RPS) | Medium (100–1000 RPS) | High (≥1000 RPS) |
|---|---|---|---|
| `slidingWindowSize` | 10 | 20–50 | 50–100 |
| `minimumNumberOfCalls` | 5 | 10–20 | 20–50 |
| `failureRateThreshold` | 50% | 50% | 40–50% |
| `waitDurationInOpenState` | 5–10s | 10–30s | 30–60s |
| `permittedCallsInHalfOpenState` | 2–3 | 3–5 | 5–10 |

**Starting rule of thumb**: `minimumNumberOfCalls` should be ≈ 5% of `slidingWindowSize` minimum viable sample; `waitDurationInOpenState` should match the typical recovery time of your downstream service.

### PromQL Alert Rules

```promql
# Alert: circuit breaker enters OPEN state
resilience4j_circuitbreaker_state{state="open"} == 1

# Alert: failure rate exceeds 40% over 1 minute
rate(resilience4j_circuitbreaker_calls_seconds_count{kind="failed"}[1m])
  /
rate(resilience4j_circuitbreaker_calls_seconds_count[1m])
> 0.4

# Alert: rate limiter rejection rate > 5%
rate(resilience4j_ratelimiter_available_permissions_total[1m]) < 0
```

Metric names (Prometheus / Micrometer):
- `resilience4j_circuitbreaker_calls_seconds_count{name,kind}` — `kind`: `successful`, `failed`, `ignored`, `not_permitted`
- `resilience4j_circuitbreaker_state{name,state}` — state: `closed`=0, `open`=1, `half_open`=2
- `resilience4j_retry_calls_total{name,kind}` — `kind`: `successful_without_retry`, `successful_with_retry`, `failed_with_retry`, `failed_without_retry`

### Troubleshooting Flow

| Symptom | Check |
|---|---|
| CB never opens even with failures | `minimumNumberOfCalls` not reached? Wrong `recordExceptions`? Verify `/actuator/health` shows CB registered |
| CB opens on expected exceptions | `ignoreExceptions` accidentally catches them? `recordFailurePredicate` returning `false`? |
| `@CircuitBreaker` annotation has no effect | Self-invocation (same class)? Private method? Non-Spring-managed bean? |
| Instance name typo | `name` in annotation ≠ YAML `instances` key → silently uses `default` config. Verify `/actuator/health` lists expected instance names |
| `@TimeLimiter` has no effect | Return type must be `Mono`, `Flux`, or `CompletableFuture` — sync methods are not timed |
| WebClient calls not recorded as failures | `WebClientResponseException` is NOT `HttpServerErrorException` — add `.onStatus()` mapping in your service |
| Coroutine test is flaky | `automaticTransitionFromOpenToHalfOpenEnabled: true` causes asynchronous OPEN→HALF_OPEN transition — disable in test instance |
| Fallback method not found | Return type mismatch? Parameter type not `Throwable` or subclass? Method in different class? |
