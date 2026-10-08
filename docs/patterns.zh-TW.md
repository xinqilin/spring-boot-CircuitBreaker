# Resilience4j 模式詳解

[回到 README](../README.zh-TW.md)

## 1. Circuit Breaker（斷路器）

透過滑動視窗追蹤呼叫結果，防止級聯故障。當失敗率超過門檻值，斷路器**開路**（OPEN），立即拒絕所有呼叫。等待一段時間後，轉換到**半開路**（HALF_OPEN）狀態探測服務是否恢復。

```
CLOSED ──(失敗率 ≥ 50%)──► OPEN ──(等待 5s)──► HALF_OPEN ──(3 次探測呼叫)──► CLOSED/OPEN
```

**設定（`basic` 實例）：**

```yaml
resilience4j.circuitbreaker.instances.basic:
  baseConfig: default          # slidingWindowSize: 10, minimumNumberOfCalls: 5
                               # failureRateThreshold: 50, waitDurationInOpenState: 5s
                               # permittedNumberOfCallsInHalfOpenState: 3
                               # automaticTransitionFromOpenToHalfOpenEnabled: true
```

**注解式：**

```kotlin
@CircuitBreaker(name = "basic", fallbackMethod = "fallback")
fun failure(): String {
    throw HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "remote error")
}

private fun fallback(ex: HttpServerErrorException): String = "已恢復：${ex.message}"
private fun fallback(ex: Exception): String = "已恢復：$ex"  // 通用 catch-all overload
```

**函式式：**

```kotlin
Decorators.ofSupplier { service.failure() }
    .withCircuitBreaker(circuitBreaker)
    .withBulkhead(bulkhead)
    .withRetry(retry)
    .get()
```

**實際測試：**

```bash
# 觸發足夠多的失敗讓斷路器開路
for i in $(seq 1 10); do curl -s http://localhost:8080/basic/failure; echo; done

# 開路後，呼叫立即被拒絕（CallNotPermittedException）
curl http://localhost:8080/basic/failure

# 查看斷路器狀態
curl http://localhost:8080/actuator/health | jq '.components.circuitBreakers'
```

---

## 2. Retry（重試）

針對暫時性錯誤自動重試，設定最多 3 次，每次間隔 100ms。

**設定：**

```yaml
resilience4j.retry.configs.default:
  maxAttempts: 3
  waitDuration: 100ms
  retryExceptions:
    - org.springframework.web.client.HttpServerErrorException
    - java.util.concurrent.TimeoutException
    - java.io.IOException
```

只有 `retryExceptions` 清單中的例外才會觸發重試。`BusinessException` 不在清單內，因此會直接往上拋出，不重試。

**注解式：**

```kotlin
@CircuitBreaker(name = "basic")
@Retry(name = "basic")
fun failure(): String {
    throw HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "remote error")
    // 最多重試 3 次，最終失敗才被斷路器記錄
}
```

**函式式：**

```kotlin
Decorators.ofSupplier(supplier)
    .withCircuitBreaker(circuitBreaker)
    .withBulkhead(bulkhead)
    .withRetry(retry)           // retry 包住 circuit breaker 外層
    .get()
```

**順序說明：** 在函式式 API 中，裝飾順序很重要。將 retry 包在 circuit breaker 外層，代表每次重試都算斷路器滑動視窗中的一次獨立呼叫。

**實際測試：**

```bash
curl http://localhost:8080/basic/failure
# 觀察應用程式 log，可以看到 3 次 retry 事件後才最終失敗
```

---

## 3. Bulkhead（艙壁）

限制同時並發的呼叫數量，防止執行緒或資源耗盡。本專案示範兩種變體：

### Semaphore Bulkhead（同步，號誌式）

使用號誌限制並發呼叫。若達到上限且超過 `maxWaitDuration` 等待時間，拋出 `BulkheadFullException`。

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

### Thread Pool Bulkhead（非同步，執行緒池式）

將呼叫提交至有界執行緒池，搭配 `CompletableFuture` 回傳型別使用。超出容量的請求會被排隊或以 `BulkheadFullException` 拒絕。

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

**函式式（執行緒池）：**

```kotlin
Decorators.ofSupplier(supplier)
    .withThreadPoolBulkhead(threadPoolBulkhead)
    .withTimeLimiter(timeLimiter, scheduledExecutorService)
    .withCircuitBreaker(circuitBreaker)
    .get().toCompletableFuture()
```

**實際測試：**

```bash
curl http://localhost:8080/basic/futureSuccess
curl http://localhost:8080/functional/futureSuccess
```

---

## 4. Time Limiter（時間限制器）

對回傳 `CompletableFuture` 或響應式型別的操作強制套用 timeout。若超過 `timeoutDuration`，拋出 `TimeoutException`，斷路器同時記錄為失敗。

```yaml
resilience4j.timelimiter.configs.default:
  timeoutDuration: 2s
  cancelRunningFuture: false
```

**注解式（Mono）：**

```kotlin
@TimeLimiter(name = "basic")
@CircuitBreaker(name = "basic", fallbackMethod = "monoFallback")
fun monoTimeout(): Mono<String> =
    Mono.just("Hello").delayElement(Duration.ofSeconds(10))  // 10s > 2s 上限 → 觸發 timeout

private fun monoFallback(ex: Exception): Mono<String> =
    Mono.just("已恢復：$ex")
```

**函式式（非同步）：**

```kotlin
Decorators.ofSupplier(supplier)
    .withThreadPoolBulkhead(threadPoolBulkhead)
    .withTimeLimiter(timeLimiter, scheduledExecutorService)   // 強制 2s timeout
    .withCircuitBreaker(circuitBreaker)
    .withFallback(listOf(TimeoutException::class.java), ::fallback)
    .get().toCompletableFuture()
```

**函式式（Mono/Flux）：**

```kotlin
publisher
    .transform(TimeLimiterOperator.of(timeLimiter))
    .transform(BulkheadOperator.of(bulkhead))
    .transform(CircuitBreakerOperator.of(circuitBreaker))
    .onErrorResume(TimeoutException::class.java, fallback)
```

**實際測試：**

```bash
curl http://localhost:8080/basic/monoTimeout      # 觸發 timeout fallback
curl http://localhost:8080/basic/futureTimeout    # 觸發 timeout + 特定型別 fallback
curl http://localhost:8080/functional/fluxTimeout
```

---

## 5. Rate Limiter（速率限制器）

透過每個刷新週期只允許固定數量的呼叫來控制呼叫速率。超出限制的呼叫最多等待 `timeoutDuration`，若仍無法取得許可則拋出 `RequestNotPermitted`。

```yaml
resilience4j.ratelimiter.instances.basic:
  limitForPeriod: 10         # 每個週期允許 10 次呼叫
  limitRefreshPeriod: 1s     # 每秒刷新一次
  timeoutDuration: 0         # 無許可時立即失敗
resilience4j.ratelimiter.instances.functional:
  limitForPeriod: 6
  limitRefreshPeriod: 500ms
  timeoutDuration: 3s        # 最多等待 3s 取得許可
```

**注解式：**

```kotlin
@RateLimiter(name = "basic", fallbackMethod = "rateLimitFallback")
@CircuitBreaker(name = "basic")
fun rateLimitedCall(): String = "Hello World from rate-limited backend basic"

private fun rateLimitFallback(ex: RequestNotPermitted): String =
    "已超出速率限制：${ex.message}"
```

**函式式（同步）：**

```kotlin
Decorators.ofSupplier { service.rateLimitedCall() }
    .withRateLimiter(rateLimiter)
    .withCircuitBreaker(circuitBreaker)
    .withBulkhead(bulkhead)
    .withFallback(listOf(RequestNotPermitted::class.java), ::fallback)
    .get()
```

**函式式（Mono）：**

```kotlin
service.monoRateLimited()
    .transform(RateLimiterOperator.of(rateLimiter))
    .transform(CircuitBreakerOperator.of(circuitBreaker))
    .transform(BulkheadOperator.of(bulkhead))
```

**實際測試（觸發速率限制）：**

```bash
# 快速連打 12 次（限制 10/s）
for i in $(seq 1 12); do curl -s http://localhost:8080/basic/rateLimited; echo; done
# 前 10 次："Hello World from rate-limited backend basic"
# 第 11 次起："Rate limit exceeded: RateLimiter 'basic' does not permit further calls"
```

---

## 6. Fallback 降級策略

本專案示範四種不同的降級方式：

### A. 注解 `fallbackMethod`（BasicService）

Resilience4j 透過例外型別匹配 method overload 來找到對應的 fallback，越精確的型別優先匹配。

```kotlin
@CircuitBreaker(name = "basic", fallbackMethod = "fallback")
fun failureWithFallback(): String = failure()

// 特定例外型別 — 優先匹配
private fun fallback(ex: HttpServerErrorException): String =
    "Recovered HttpServerErrorException: ${ex.message}"

// 通用 catch-all — 無精確 overload 時使用
private fun fallback(ex: Exception): String = "Recovered: $ex"

// CompletableFuture 回傳型別 — fallback 也必須回傳 CompletableFuture
private fun futureFallback(ex: TimeoutException): CompletableFuture<String> =
    CompletableFuture.completedFuture("Recovered TimeoutException: $ex")

private fun futureFallback(ex: BulkheadFullException): CompletableFuture<String> =
    CompletableFuture.completedFuture("Recovered BulkheadFullException: $ex")

private fun futureFallback(ex: CallNotPermittedException): CompletableFuture<String> =
    CompletableFuture.completedFuture("Recovered CallNotPermittedException: $ex")
```

### B. `Decorators.withFallback`（FunctionalStyleController）

用於 `CompletableFuture` 鏈路的程式化降級。

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

### C. Reactor `onErrorResume`（FunctionalStyleController）

在 Mono/Flux pipeline 上按例外型別鏈式掛載降級邏輯。

```kotlin
publisher
    .transform(TimeLimiterOperator.of(timeLimiter))
    .transform(CircuitBreakerOperator.of(circuitBreaker))
    .onErrorResume(TimeoutException::class.java) { ex -> Mono.just("Timeout: $ex") }
    .onErrorResume(CallNotPermittedException::class.java) { ex -> Mono.just("Circuit open: $ex") }
    .onErrorResume(BulkheadFullException::class.java) { ex -> Mono.just("Bulkhead full: $ex") }
```

### D. Vavr `Try`（FunctionalService）

與 Resilience4j 無關的純函數式降級，用於 `failureWithFallback()`。

```kotlin
Try.ofSupplier(::failure)
    .recover { ex: Throwable -> fallback(ex) }
    .get()
```

---

## 失敗分類

兩個實例的分類結果相同，只是機制不同（`basic`：`recordExceptions` 清單；`functional`：`RecordFailurePredicate`）：

| 分類 | 範例 | 效果 |
|---|---|---|
| **記錄**（計入失敗） | `HttpServerErrorException`、`TimeoutException`、`IOException` | 增加滑動視窗的失敗計數 |
| **不記錄**（計為成功） | `HttpClientErrorException`（4xx）、`BusinessException` | 例外照樣往上拋給呼叫端；這次呼叫以「成功」計入滑動視窗 |

注意與 `ignoreExceptions` 的差別：被 *ignore* 的例外完全不計入，而「不記錄」的例外會計為成功。兩個實例都沒有使用 `ignoreExceptions`。

```kotlin
// RecordFailurePredicate.kt — 由 'functional' 斷路器實例使用
class RecordFailurePredicate : Predicate<Throwable> {
    override fun test(t: Throwable): Boolean =
        t !is BusinessException && t !is HttpClientErrorException  // 其餘都記錄為失敗
}
```
