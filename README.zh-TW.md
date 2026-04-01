# Resilience4j Spring Boot 示範專案

以 **兩種平行實作風格** 展示 Resilience4j 五大容錯模式的 Spring Boot 示範應用程式：

| 風格 | 端點 | 實作方式 |
|---|---|---|
| **Annotation 注解式** | `/basic/*` | `@CircuitBreaker`、`@Retry`、`@Bulkhead`、`@TimeLimiter`、`@RateLimiter` |
| **Functional API 函式式** | `/functional/*` | `Decorators` 建構器 + Reactor 運算子（`CircuitBreakerOperator` 等） |

兩個 Controller 對相同的 Service 邏輯暴露完全一致的端點，可直接比較兩種風格的差異。

---

## 技術棧

- **Java 21** / **Kotlin 2.2.20**
- **Spring Boot 4.0.5**
- **Gradle 9.4.1**
- **Resilience4j 2.4.0**
- **Project Reactor**（Mono / Flux / CompletableFuture）
- **Micrometer + Prometheus** 指標
- **Spring Actuator** 健康指標

---

## 快速開始

```bash
# 編譯
./gradlew build

# 啟動應用程式（port 8080）
./gradlew bootRun

# 執行測試
./gradlew test

# 啟動監控堆疊（Prometheus + Grafana）
docker-compose up -d
```

- 應用程式：http://localhost:8080
- Actuator：http://localhost:8080/actuator
- Prometheus：http://localhost:9090
- Grafana：http://localhost:3000

---

## 架構說明

### 兩種實作風格

```
com.bill.circuitBreaker/
├── controller/
│   ├── BasicController.kt            # /basic/* — 委派給 BasicService
│   └── FunctionalStyleController.kt  # /functional/* — 在 Controller 內建立 Decorator 鏈
├── service/
│   ├── Service.kt                    # 共用介面
│   └── impl/
│       ├── BasicService.kt           # 注解式（@CircuitBreaker、@Retry…）
│       └── FunctionalService.kt      # 純業務邏輯，resilience 在 Controller 層套用
├── config/
│   └── ApplicationConfig.kt          # 客製化設定 + Registry 事件消費者
└── exception/
    ├── BusinessException.kt           # 被 Circuit Breaker 忽略的例外
    └── RecordFailurePredicate.kt      # 'functional' 實例的自訂失敗判斷邏輯
```

**注解式** 讓業務邏輯保持乾淨——resilience 以 metadata 的形式宣告，AOP Proxy 攔截呼叫後透明地套用整條鏈。

**函式式** 讓 resilience 鏈路變得顯式且可組合。`FunctionalStyleController` 在執行期建立鏈路：
- `Decorators.ofSupplier(…).withCircuitBreaker(…).withBulkhead(…).withRetry(…).get()` — 同步呼叫
- `.transform(BulkheadOperator.of(bulkhead)).transform(CircuitBreakerOperator.of(cb))` — Mono/Flux
- `Decorators.ofSupplier(…).withThreadPoolBulkhead(…).withTimeLimiter(…)…get().toCompletableFuture()` — 非同步

### 實例設定

`application.yaml` 定義了兩個具名實例：`basic`（注解式）和 `functional`（函式式）。`functional` 實例使用自訂的 `RecordFailurePredicate`，將 `BusinessException` 排除在失敗記錄之外。

---

## Resilience4j 模式詳解

### 1. Circuit Breaker（斷路器）

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

### 2. Retry（重試）

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

### 3. Bulkhead（艙壁）

限制同時並發的呼叫數量，防止執行緒或資源耗盡。本專案示範兩種變體：

#### Semaphore Bulkhead（同步，號誌式）

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

#### Thread Pool Bulkhead（非同步，執行緒池式）

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

### 4. Time Limiter（時間限制器）

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

### 5. Rate Limiter（速率限制器）

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

### 6. Fallback 降級策略

本專案示範四種不同的降級方式：

#### A. 注解 `fallbackMethod`（BasicService）

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

#### B. `Decorators.withFallback`（FunctionalStyleController）

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

#### C. Reactor `onErrorResume`（FunctionalStyleController）

在 Mono/Flux pipeline 上按例外型別鏈式掛載降級邏輯。

```kotlin
publisher
    .transform(TimeLimiterOperator.of(timeLimiter))
    .transform(CircuitBreakerOperator.of(circuitBreaker))
    .onErrorResume(TimeoutException::class.java) { ex -> Mono.just("Timeout: $ex") }
    .onErrorResume(CallNotPermittedException::class.java) { ex -> Mono.just("Circuit open: $ex") }
    .onErrorResume(BulkheadFullException::class.java) { ex -> Mono.just("Bulkhead full: $ex") }
```

#### D. Vavr `Try`（FunctionalService）

與 Resilience4j 無關的純函數式降級，用於 `failureWithFallback()`。

```kotlin
Try.ofSupplier(::failure)
    .recover { ex: Throwable -> fallback(ex) }
    .get()
```

---

## 失敗分類

斷路器區分「記錄」與「忽略」的例外：

| 分類 | 範例 | 效果 |
|---|---|---|
| **記錄**（計入失敗） | `HttpServerErrorException`、`TimeoutException`、`IOException` | 增加滑動視窗的失敗計數 |
| **忽略**（透明通過） | `HttpClientErrorException`（4xx）、`BusinessException` | 不影響斷路器狀態，直接往上拋出 |
| **自訂 Predicate**（`functional` 實例） | 透過 `RecordFailurePredicate` | 將 `BusinessException` 明確排除在失敗記錄之外 |

```kotlin
// RecordFailurePredicate.kt — 由 'functional' 斷路器實例使用
class RecordFailurePredicate : Predicate<Throwable> {
    override fun test(t: Throwable): Boolean =
        t !is BusinessException  // 忽略 BusinessException，其餘都記錄為失敗
}
```

---

## 快速套用指南

如何在幾分鐘內將 Resilience4j 加入你的 Spring Boot 專案。

### 1. 加入依賴

```kotlin
// build.gradle.kts
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-aspectj") // 注解模式必備
    implementation("io.github.resilience4j:resilience4j-spring-boot4:2.4.0")
    implementation("io.github.resilience4j:resilience4j-reactor:2.4.0")    // 只有使用 Mono/Flux 才需要
}
```

> Spring Boot 3.x 用 `spring-boot-starter-aop` 和 `resilience4j-spring-boot3`。

### 2. 最小化 YAML 設定

複製以下設定作為起點，依實際需求調整數值：

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

# 啟用健康指標
management.health.circuitbreakers.enabled: true
management.health.ratelimiters.enabled: true
management.endpoint.health.show-details: always
```

### 3. 注解模式 — 可直接複製的起手式

所有注解的 `name` 值對應 YAML 裡的 `instances` key，依需求組合使用。

**斷路器 + Fallback：**

```kotlin
@CircuitBreaker(name = "myService", fallbackMethod = "fallback")
fun callRemoteService(): String {
    // 你的 HTTP 呼叫、DB 查詢等
}

// fallback 的回傳型別必須與原方法相同
// 可針對不同例外型別建立多個 overload，越精確的越優先
private fun fallback(ex: HttpServerErrorException): String = "服務不可用：${ex.message}"
private fun fallback(ex: Exception): String = "服務不可用"
```

**斷路器 + 重試 + Bulkhead（最常見組合）：**

```kotlin
@CircuitBreaker(name = "myService")
@Retry(name = "myService")
@Bulkhead(name = "myService")
fun callRemoteService(): String { ... }
```

**加上 Timeout（需要 CompletableFuture 或 Mono/Flux 回傳型別）：**

```kotlin
// CompletableFuture
@Bulkhead(name = "myService", type = Bulkhead.Type.THREADPOOL)
@TimeLimiter(name = "myService")
@CircuitBreaker(name = "myService", fallbackMethod = "fallback")
fun callRemoteService(): CompletableFuture<String> {
    return CompletableFuture.supplyAsync { /* 你的呼叫 */ }
}

private fun fallback(ex: TimeoutException): CompletableFuture<String> =
    CompletableFuture.completedFuture("請求逾時")

// Mono（WebFlux）
@TimeLimiter(name = "myService")
@CircuitBreaker(name = "myService", fallbackMethod = "fallback")
fun callRemoteService(): Mono<String> {
    return webClient.get().retrieve().bodyToMono(String::class.java)
}

private fun fallback(ex: Exception): Mono<String> = Mono.just("請求逾時")
```

**速率限制器：**

```kotlin
@RateLimiter(name = "myService", fallbackMethod = "rateLimitFallback")
fun callRemoteService(): String { ... }

private fun rateLimitFallback(ex: RequestNotPermitted): String = "請求過於頻繁，請稍後再試"
```

### 4. 函式式 API — 可直接複製的起手式

當你需要動態決定是否套用某個模式（例如條件式 retry），或希望鏈路在程式碼中清晰可見時使用。

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
        return Decorators.ofSupplier { /* 你的呼叫 */ }
            .withCircuitBreaker(circuitBreaker)
            .withBulkhead(bulkhead)
            .withRetry(retry)
            .withFallback(listOf(Exception::class.java)) { ex -> "已恢復：${ex.message}" }
            .get()
    }

    // Mono 版本
    fun callRemoteServiceReactive(): Mono<String> {
        return Mono.fromSupplier { /* 你的呼叫 */ }
            .transform(BulkheadOperator.of(bulkhead))
            .transform(CircuitBreakerOperator.of(circuitBreaker))
            .transform(RetryOperator.of(retry))
            .onErrorResume(CallNotPermittedException::class.java) { Mono.just("斷路器已開路") }
    }
}
```

### 5. 注解執行順序

注解的執行順序是**最外層優先**（程式碼中寫在最前面的注解是 AOP Proxy 最外層，最先執行）：

```kotlin
// 這個順序：
@CircuitBreaker(name = "x")   // 最外層 — 最先執行
@Retry(name = "x")            // 中間層
@Bulkhead(name = "x")         // 最內層 — 最後執行（最靠近實際呼叫）
fun myMethod(): String { ... }
```

大多數場景的建議順序：

```
@CircuitBreaker → @Bulkhead → @TimeLimiter → @Retry → @RateLimiter → 實際呼叫
```

**為什麼順序重要：**
- `@Retry` 在 `@CircuitBreaker` 內層 → 每次 retry 都算斷路器滑動視窗裡的一次失敗記錄，一個邏輯操作可能觸發多次失敗計數。
- `@Retry` 在 `@CircuitBreaker` 外層 → 若斷路器在 retry 中途開路，整個 retry 立即中止，對大多數場景更直觀。

### 6. Fallback Method 規則

| 規則 | 範例 |
|---|---|
| 回傳型別必須與原方法相同 | `fun callX(): String` → `fun fallback(ex: Exception): String` |
| `Mono` 方法的 fallback 也要回傳 `Mono` | `fun fallback(ex: Exception): Mono<String>` |
| `CompletableFuture` 的 fallback 也要回傳 `CompletableFuture` | `fun fallback(ex: Exception): CompletableFuture<String>` |
| 多個 overload — 越精確的型別越優先 | `fallback(ex: TimeoutException)` 優先於 `fallback(ex: Exception)` |
| 必須在同一個 class 內 | AOP Proxy 無法跨 bean 呼叫 |
| Private 可見性沒問題 | `private fun fallback(...)` 對注解式 fallback 完全有效 |

### 7. 常見陷阱

**`@Transactional` + `@CircuitBreaker` 放在同一個方法上：**

兩者都使用 AOP Proxy，疊加時行為可能不如預期。建議分層處理：`@Transactional` 放在 Repository/Service 層，`@CircuitBreaker` 放在呼叫端。

**同 class 內的自我呼叫（self-invocation）無效：**

從同一個 class 內呼叫帶注解的方法會繞過 AOP Proxy，導致注解完全沒有效果。請將被注解的方法抽到獨立的 Spring Bean。

```kotlin
// 錯誤 — 自我呼叫
class MyService {
    fun doWork() {
        callRemote()  // AOP Proxy 被繞過，@CircuitBreaker 完全沒用
    }

    @CircuitBreaker(name = "x")
    fun callRemote(): String { ... }
}

// 正確 — 注入 Bean 或拆成獨立元件
```

**`@TimeLimiter` 只對非同步回傳型別有效：**

`@TimeLimiter` 對回傳 `String`、`void` 等同步型別的方法沒有任何作用，必須搭配 `CompletableFuture<T>`、`Mono<T>` 或 `Flux<T>` 才能運作。

**Instance name 打錯字 → 靜默使用 default 設定：**

如果注解的 `name` 與 YAML 的 `instances` key 不符，Resilience4j 不會報錯，而是靜默使用 `default` 設定。務必用 `/actuator/health` 驗證實例是否正確建立。

```bash
# 驗證實例是否正確建立
curl http://localhost:8080/actuator/health | jq '.components.circuitBreakers.details | keys'
# 應該看到你設定的實例名稱，例如 ["myService"]
```

---

## 端點參考

所有端點皆為 `GET`。`/basic/*` 和 `/functional/*` 暴露完全相同的路徑。

| 路徑（後綴） | 套用模式 | 說明 |
|---|---|---|
| `success` | CB + Bulkhead + Retry | 回傳成功回應 |
| `failure` | CB + Bulkhead + Retry | 永遠拋出 `HttpServerErrorException` |
| `successException` | CB + Bulkhead | 拋出 `HttpClientErrorException`（4xx，被 CB 忽略） |
| `ignore` | CB + Bulkhead | 拋出 `BusinessException`（被 CB 忽略） |
| `fallback` | CB with fallback | 呼叫 `failure()` 後透過 fallback method 恢復 |
| `monoSuccess` | CB + Bulkhead + Retry + TimeLimiter | 響應式成功（`Mono`） |
| `monoFailure` | CB + Bulkhead + Retry | 響應式失敗（`IOException`） |
| `monoTimeout` | CB（fallback）+ Bulkhead + TimeLimiter | 響應式 timeout（10s > 2s 上限） |
| `fluxSuccess` | CB + Retry + TimeLimiter | Flux 成功（`Hello`、`World`） |
| `fluxFailure` | CB + Bulkhead + Retry | Flux 失敗（`IOException`） |
| `fluxTimeout` | CB（fallback）+ TimeLimiter | Flux timeout |
| `futureSuccess` | CB + Retry + TimeLimiter + ThreadPool Bulkhead | 非同步成功 |
| `futureFailure` | CB + Retry + TimeLimiter + ThreadPool Bulkhead | 非同步失敗 |
| `futureTimeout` | CB（fallback）+ TimeLimiter + ThreadPool Bulkhead | 非同步 timeout |
| `rateLimited` | RateLimiter（10/s）+ CB + Bulkhead | 受速率限制的同步呼叫 |
| `monoRateLimited` | RateLimiter（10/s）+ CB + Bulkhead | 受速率限制的響應式呼叫 |

---

## 設定參考

```yaml
resilience4j.circuitbreaker:
  configs:
    default:
      slidingWindowSize: 10            # 滑動視窗追蹤的呼叫次數
      minimumNumberOfCalls: 5          # 開始計算失敗率前的最小呼叫次數
      failureRateThreshold: 50         # 失敗率門檻（%），超過則開路
      waitDurationInOpenState: 5s      # 開路後等待多久進入 HALF_OPEN
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
      maxConcurrentCalls: 10           # 號誌式：最多 10 個並發呼叫
    functional:
      maxConcurrentCalls: 20
      maxWaitDuration: 10ms            # 等待取得許可的最長時間，超過則拒絕

resilience4j.thread-pool-bulkhead:
  configs:
    default:
      maxThreadPoolSize: 4
      coreThreadPoolSize: 2
      queueCapacity: 2

resilience4j.ratelimiter:
  instances:
    basic:
      limitForPeriod: 10               # 每個刷新週期的許可數量
      limitRefreshPeriod: 1s
      timeoutDuration: 0               # 無許可時立即失敗
    functional:
      limitForPeriod: 6
      limitRefreshPeriod: 500ms
      timeoutDuration: 3s              # 最多等待 3s 取得許可

resilience4j.timelimiter:
  configs:
    default:
      timeoutDuration: 2s
      cancelRunningFuture: false
```

---

## 監控

使用 Docker 啟動監控堆疊：

```bash
docker-compose up -d
```

包含：
- **Prometheus**：http://localhost:9090 — 每 5 秒抓取 `/actuator/prometheus`
- **Grafana**：http://localhost:3000 — 預設帳密：`admin` / `admin`

### 關鍵指標

```
# 斷路器呼叫結果
resilience4j_circuitbreaker_calls_seconds_count{kind="successful"}
resilience4j_circuitbreaker_calls_seconds_count{kind="failed"}
resilience4j_circuitbreaker_calls_seconds_count{kind="not_permitted"}

# 斷路器狀態（0=CLOSED, 1=OPEN, 2=HALF_OPEN）
resilience4j_circuitbreaker_state

# 速率限制器
resilience4j_ratelimiter_available_permissions
resilience4j_ratelimiter_waiting_threads

# 重試
resilience4j_retry_calls_total{kind="successful_with_retry"}
resilience4j_retry_calls_total{kind="failed_with_retry"}

# Bulkhead
resilience4j_bulkhead_available_concurrent_calls
resilience4j_bulkhead_max_allowed_concurrent_calls
```

### Actuator 健康端點

```bash
curl http://localhost:8080/actuator/health | jq '.'
```

健康回應包含每個斷路器和速率限制器的即時狀態：

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

其他實用的 Actuator 端點：

```bash
# 所有斷路器事件（狀態轉換、成功/失敗呼叫）
curl http://localhost:8080/actuator/circuitbreakerevents

# 特定實例的事件
curl http://localhost:8080/actuator/circuitbreakerevents/basic

# 重試事件
curl http://localhost:8080/actuator/retryevents

# 速率限制器事件
curl http://localhost:8080/actuator/ratelimiterevents

# 所有暴露的指標
curl http://localhost:8080/actuator/metrics
```
