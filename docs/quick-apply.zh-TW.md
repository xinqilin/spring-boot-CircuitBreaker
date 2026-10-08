# 快速套用指南

[回到 README](../README.zh-TW.md)

如何在幾分鐘內將 Resilience4j 加入你的 Spring Boot 專案。

## 1. 加入依賴

```kotlin
// build.gradle.kts
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-aspectj") // 注解模式必備
    implementation("io.github.resilience4j:resilience4j-spring-boot4:2.4.0")
    implementation("io.github.resilience4j:resilience4j-reactor:2.4.0")    // 只有使用 Mono/Flux 才需要
}
```

> Spring Boot 3.x 用 `spring-boot-starter-aop` 和 `resilience4j-spring-boot3`。

## 2. 最小化 YAML 設定

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

## 3. 注解模式 — 可直接複製的起手式

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

## 4. 函式式 API — 可直接複製的起手式

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

## 5. 注解執行順序

注解在**程式碼中的書寫順序不影響執行順序**。Resilience4j 的 Spring aspect 永遠依固定順序套用（最外層在前）：

```
Retry ( CircuitBreaker ( RateLimiter ( TimeLimiter ( Bulkhead ( 實際呼叫 ) ) ) ) )
```

要改變順序，需設定 aspect order 屬性（數值越大、優先權越高、越外層），或改用函式式 `Decorators` / Reactor operator 寫法：

```yaml
resilience4j:
  retry:
    retryAspectOrder: 2
  circuitbreaker:
    circuitBreakerAspectOrder: 1
```

**為什麼順序重要：**
- 預設順序下 `@Retry` 包住 `@CircuitBreaker` → 每次 retry 都會記入斷路器的滑動視窗。呼叫一次 `/basic/failure`（共 3 次嘗試）會記錄 3 次失敗。
- 斷路器開路後，下一次嘗試直接拋出 `CallNotPermittedException`；它不在 `retryExceptions` 內，因此 retry 隨即停止。

## 6. Fallback Method 規則

| 規則 | 範例 |
|---|---|
| 回傳型別必須與原方法相同 | `fun callX(): String` → `fun fallback(ex: Exception): String` |
| `Mono` 方法的 fallback 也要回傳 `Mono` | `fun fallback(ex: Exception): Mono<String>` |
| `CompletableFuture` 的 fallback 也要回傳 `CompletableFuture` | `fun fallback(ex: Exception): CompletableFuture<String>` |
| 多個 overload — 越精確的型別越優先 | `fallback(ex: TimeoutException)` 優先於 `fallback(ex: Exception)` |
| 必須在同一個 class 內 | AOP Proxy 無法跨 bean 呼叫 |
| Private 可見性沒問題 | `private fun fallback(...)` 對注解式 fallback 完全有效 |

## 7. 常見陷阱

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

## 8. WebClient / RestClient 整合

最常見的真實場景：把熔斷器套在 HTTP 下游呼叫上。

**關鍵差異**：`WebClient` 在遇到 HTTP 錯誤狀態碼時拋出 `WebClientResponseException`（不是 `HttpServerErrorException`），因此必須用 `.onStatus()` 把 5xx 對映成有被記錄的例外。`RestClient` 預設會拋出 `HttpServerErrorException`，不需要額外 mapping。

**WebClient — 注解風格（Kotlin）**

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
            // 把 WebClientResponseException 對映到 IOException（在 recordExceptions 清單內）
            Mono.error(IOException("Downstream 5xx"))
        }
        .bodyToMono(String::class.java)

    private fun fallback(ex: Exception): Mono<String> =
        Mono.just("fallback: ${ex.javaClass.simpleName}")
}
```

**WebClient — 函式風格（Reactor 運算子）**

```kotlin
fun fetchDataFunctional(): Mono<String> = webClient.get()
    .uri("/api/data")
    .retrieve()
    .onStatus(HttpStatusCode::is5xxServerError) { Mono.error(IOException("5xx")) }
    .bodyToMono(String::class.java)
    .transform(CircuitBreakerOperator.of(circuitBreaker))  // CB 包住 retry
    .transform(RetryOperator.of(retry))
    .onErrorReturn(CallNotPermittedException::class.java, "熔斷器開路 — 使用備用值")
```

**RestClient — 注解風格（Java 21）**

```java
// RestClient 對 5xx 預設拋出 HttpServerErrorException，與 recordExceptions 天生吻合。
@CircuitBreaker(name = "downstream", fallbackMethod = "fallback")
public DownstreamResponse fetchData() {
    var body = restClient.get()
        .uri("/api/data")
        .retrieve()
        .body(String.class);             // 5xx 時自動拋出 HttpServerErrorException
    return new DownstreamResponse(body, "live");
}

// Java 21 型別模式 switch 處理 fallback
private DownstreamResponse fallback(Throwable ex) {
    var message = switch (ex) {
        case HttpServerErrorException e -> "5xx: " + e.getStatusCode();
        case CallNotPermittedException e -> "熔斷器開路";
        default -> "fallback: " + ex.getMessage();
    };
    return new DownstreamResponse(message, "fallback");
}
```

**共用 Bean（建議作法）**

`WebClient` 和 `RestClient` 都是執行緒安全的，應定義為 `@Bean` 單例，統一管理 timeout 與 base URL：

```kotlin
@Configuration
class HttpClientsConfig {
    @Bean fun webClient(): WebClient = WebClient.builder().baseUrl(baseUrl).build()
    @Bean fun restClient(): RestClient = RestClient.builder().baseUrl(baseUrl).build()
}
```

**驗證指令**

```bash
curl localhost:8080/example/webclient/success    # WebClient 正常路徑
curl localhost:8080/example/webclient/failure    # 觸發 CB 失敗計數
curl localhost:8080/example/restclient/success   # RestClient 正常路徑（Java）
curl localhost:8080/example/restclient/failure   # 觸發 CB 失敗計數
```

## 9. Coroutine 整合

Resilience4j 提供 `executeSuspendFunction` Kotlin 擴充函式，讓你不需要 `Mono`/`CompletableFuture` 包裝，直接在 `suspend` lambda 內套用熔斷器。

**依賴**（需與 `resilience4j-all` 並列宣告）：

```kotlin
implementation("io.github.resilience4j:resilience4j-kotlin:2.4.0")
```

**熔斷器包住 suspend lambda**

```kotlin
@Component
class MyService(circuitBreakerRegistry: CircuitBreakerRegistry) {
    private val cb = circuitBreakerRegistry.circuitBreaker("myService")

    suspend fun fetchData(): String = cb.executeSuspendFunction {
        // 任意 suspend 內容，不需要 Mono 包裝，不會阻塞 coroutine dispatcher
        externalApi.getData()
    }
}
```

**組合 CB + Retry**

```kotlin
suspend fun fetchWithRetry(): String = retry.executeSuspendFunction {
    cb.executeSuspendFunction {
        externalApi.getData()
    }
}
// Retry 是外層裝飾器：每次重試都會重新進入 CB 檢查。
```

**Suspend 控制器（Spring MVC / WebFlux 均支援）**

```kotlin
@RestController
class MyController(private val myService: MyService) {

    @GetMapping("/data")
    suspend fun data(): String = myService.fetchData()
    // Spring 偵測到 suspend，透過 kotlinx-coroutines-reactor adapter 轉換。
    // 不需要回傳 Mono 或 CompletableFuture。
}
```

**Coroutine 內的 Fallback 處理**

```kotlin
suspend fun fetchSafe(): String = try {
    cb.executeSuspendFunction { externalApi.getData() }
} catch (ex: CallNotPermittedException) {
    "熔斷器開路 — 使用快取值"
} catch (ex: IOException) {
    "下游服務不可用"
}
```

> **注意**：在 `suspend` 函式內呼叫 `Mono.block()` 會阻塞 coroutine dispatcher 執行緒，完全失去 coroutine 的優勢，請避免。

**驗證指令**

```bash
curl localhost:8080/example/coroutine/success   # suspend fun 正常路徑
curl localhost:8080/example/coroutine/failure   # 觸發 CB 失敗計數
curl localhost:8080/example/coroutine/retry     # 重試耗盡後回應
```

## 10. 狀態轉換測試

測試熔斷器行為需要直接強制狀態，或用受控的失敗驅動狀態機轉換。

**測試專用 instance 設定** — 使用獨立 YAML instance 避免污染生產指標，並確保測試確定性：

```yaml
resilience4j.circuitbreaker.instances:
  test:
    slidingWindowSize: 5
    minimumNumberOfCalls: 5
    failureRateThreshold: 60
    waitDurationInOpenState: 10s
    automaticTransitionFromOpenToHalfOpenEnabled: false  # 關閉非同步狀態轉換，讓斷言更穩定
    permittedNumberOfCallsInHalfOpenState: 2
```

**強制轉換（最快 — 用於隔離測試 fallback 邏輯）**

```kotlin
@SpringBootTest
class CircuitBreakerTest {
    @Autowired lateinit var registry: CircuitBreakerRegistry
    private lateinit var cb: CircuitBreaker

    @BeforeEach fun setUp() {
        cb = registry.circuitBreaker("test")
        cb.reset()  // 轉換至 CLOSED + 清空 sliding window
    }

    @Test fun `強制開路後呼叫被拒絕`() {
        cb.transitionToOpenState()
        assertThat(cb.state).isEqualTo(CircuitBreaker.State.OPEN)
        assertThatThrownBy { CircuitBreaker.decorateSupplier(cb) { "x" }.get() }
            .isInstanceOf(CallNotPermittedException::class.java)
    }
}
```

**連續失敗驅動 CLOSED → OPEN**

```kotlin
@Test fun `連續失敗觸發熔斷`() {
    repeat(5) {
        runCatching { cb.executeCheckedSupplier { throw IOException("fail") } }
    }
    // 5 次失敗 / 5 次呼叫 = 100% > 60% 閾值 → OPEN
    assertThat(cb.state).isEqualTo(CircuitBreaker.State.OPEN)
}
```

**Java 21 等效寫法 — 用型別模式 switch 判斷狀態**

```java
@Test
void forcedTransition() {
    cb.transitionToOpenState();
    var description = switch (cb.getState()) {
        case OPEN      -> "熔斷器開路 — 呼叫被拒絕";
        case CLOSED    -> "熔斷器正常 — 允許呼叫";
        case HALF_OPEN -> "半開狀態 — 允許有限呼叫";
        default        -> "其他狀態: " + cb.getState();
    };
    assertThat(description).startsWith("熔斷器開路");
}
```

**StepVerifier 驗證 Reactive Pipeline**

```kotlin
@Test fun `開路時 Mono 發出 CallNotPermittedException`() {
    cb.transitionToOpenState()
    StepVerifier.create(
        Mono.just("data").transform(CircuitBreakerOperator.of(cb))
    )
        .expectError(CallNotPermittedException::class.java)
        .verify()
}

@Test fun `onErrorReturn 提供備用值`() {
    cb.transitionToOpenState()
    StepVerifier.create(
        Mono.just("data")
            .transform(CircuitBreakerOperator.of(cb))
            .onErrorReturn(CallNotPermittedException::class.java, "備用值")
    )
        .expectNext("備用值")
        .verifyComplete()
}
```

## 11. 運維指南

### 決策矩陣

**注解風格 vs 函式 API**

| 判斷依據 | 注解風格 | 函式 API |
|---|---|---|
| 你擁有這個方法的原始碼 | 建議 | 可選 |
| 呼叫第三方 library（無法加注解） | 不可行 | 用 `Decorators.ofSupplier` |
| Reactive 鏈（Mono/Flux） | 可行（搭配 `@TimeLimiter`） | `.transform()` — 更明確 |
| Kotlin Coroutine | 不支援 | 用 `executeSuspendFunction` |
| 多個模式疊加 | 可行（需注意順序） | 可行（順序在程式碼中顯而易見） |

**Semaphore vs Thread Pool Bulkhead**

| 判斷依據 | Semaphore（`SEMAPHORE`） | Thread Pool（`THREADPOOL`） |
|---|---|---|
| 回傳型別 | `String`、`Mono<T>`、`Flux<T>` | 僅 `CompletableFuture<T>` |
| 資源消耗 | 低（僅計數器） | 高（專屬執行緒池） |
| 呼叫執行緒 | 呼叫方執行方法 | 卸載到池執行緒 |
| Timeout 支援 | 無 | 有（搭配 `@TimeLimiter`） |
| 適用情境 | 限制快速呼叫的並發數 | 隔離緩慢或阻塞的呼叫 |

### 生產調參速查表

| 參數 | 低流量（≤100 RPS） | 中流量（100–1000 RPS） | 高流量（≥1000 RPS） |
|---|---|---|---|
| `slidingWindowSize` | 10 | 20–50 | 50–100 |
| `minimumNumberOfCalls` | 5 | 10–20 | 20–50 |
| `failureRateThreshold` | 50% | 50% | 40–50% |
| `waitDurationInOpenState` | 5–10s | 10–30s | 30–60s |
| `permittedCallsInHalfOpenState` | 2–3 | 3–5 | 5–10 |

**起手法則**：`minimumNumberOfCalls` 大約取 `slidingWindowSize` 的 5–10%；`waitDurationInOpenState` 對應下游服務的典型恢復時間。

### PromQL 告警規則

```promql
# 告警：熔斷器進入 OPEN 狀態
resilience4j_circuitbreaker_state{state="open"} == 1

# 告警：1 分鐘內失敗率超過 40%
rate(resilience4j_circuitbreaker_calls_seconds_count{kind="failed"}[1m])
  /
rate(resilience4j_circuitbreaker_calls_seconds_count[1m])
> 0.4

# 告警：限流器拒絕率上升
rate(resilience4j_ratelimiter_available_permissions_total[1m]) < 0
```

常用 Metric 名稱（Prometheus / Micrometer）：
- `resilience4j_circuitbreaker_calls_seconds_count{name,kind}` — `kind`: `successful`、`failed`、`ignored`、`not_permitted`
- `resilience4j_circuitbreaker_state{name,state}` — state: `closed`=0, `open`=1, `half_open`=2
- `resilience4j_retry_calls_total{name,kind}` — `kind`: `successful_without_retry`、`successful_with_retry`、`failed_with_retry`

### 常見問題排查表

| 症狀 | 檢查項目 |
|---|---|
| 失敗很多但 CB 從未開路 | `minimumNumberOfCalls` 未達到？`recordExceptions` 沒包含實際拋出的例外型別？用 `/actuator/health` 確認 CB 有正確建立 |
| CB 對預期的例外開路 | `ignoreExceptions` 意外把它們排除了？`recordFailurePredicate` 回傳 `false`？ |
| `@CircuitBreaker` 注解完全無效 | 自我呼叫（同 class）？私有方法？Bean 不受 Spring 管理？ |
| Instance name 打錯字 | 注解的 `name` 與 YAML `instances` key 不符 → 靜默使用 `default` 設定。用 `/actuator/health` 驗證 |
| `@TimeLimiter` 無效 | 回傳型別必須是 `Mono`、`Flux` 或 `CompletableFuture`，同步方法不支援 |
| WebClient 呼叫未被記錄為失敗 | `WebClientResponseException` ≠ `HttpServerErrorException`，需在 Service 層加 `.onStatus()` mapping |
| Coroutine 測試不穩定 | `automaticTransitionFromOpenToHalfOpenEnabled: true` 導致非同步狀態轉換，在 test instance 關閉此選項 |
| Fallback 方法找不到 | 回傳型別不符？參數不是 `Throwable` 或其子類？方法在不同 class？ |

## 12. Spring Framework 7 內建 Resilience vs Resilience4j

Spring Framework 7（隨 Spring Boot 4 推出）在核心框架加入了 `@Retryable` 與 `@ConcurrencyLimit`。它們**不會被自動設定** —— 需要在 `@Configuration` class 加上 `@EnableResilientMethods`。程式碼：[`example/springcore`](../src/main/kotlin/com/bill/circuitBreaker/example/springcore/)，測試：[`SpringCoreVsResilience4jTest`](../src/test/kotlin/com/bill/circuitBreaker/example/SpringCoreVsResilience4jTest.kt)。

| 需求 | Spring Framework 7 內建 | Resilience4j |
|---|---|---|
| 重試 | `@Retryable(includes, maxRetries, delay, jitter, multiplier, maxDelay)`；也能裝飾 `Mono` / `Flux` | `@Retry` + YAML 實例、`RetryOperator` |
| 併發上限 | `@ConcurrencyLimit(limit, policy = BLOCK \| REJECT)` | `@Bulkhead`（semaphore 搭配 `maxWaitDuration`，或 thread pool） |
| 斷路器 / 限流 / 逾時 | 無 | `@CircuitBreaker`、`@RateLimiter`、`@TimeLimiter` |
| Fallback | 無 —— 需自行 catch 例外 | `fallbackMethod`、`Decorators.withFallback` |
| 設定方式 | annotation 屬性（`*String` 版本支援 placeholder） | 具名 YAML 實例，可共用 base config |
| 可觀測性 | `MethodRetryEvent` application event | Micrometer 指標、actuator 健康指標、事件消費者 |

```kotlin
@Retryable(includes = [IOException::class], maxRetries = 2, delay = 100) // 總共 3 次嘗試
@Throws(IOException::class) // Kotlin：少了它，CGLIB proxy 會把 checked exception 包成 UndeclaredThrowableException
fun alwaysFails(): String

@ConcurrencyLimit(limit = 2, policy = ConcurrencyLimit.ThrottlePolicy.REJECT)
fun limitedReject(): String // 超出上限的呼叫拿到 InvocationRejectedException
```

**Virtual threads：** 開啟 `spring.threads.virtual.enabled=true` 後，不再有 request thread pool 充當隱性的併發上限，下游可能被大量請求淹沒。測試以 virtual threads 同時發出 20 個呼叫，上限為 2：

| 機制 | 超出上限的呼叫 | 最高併發數 |
|---|---|---|
| `@ConcurrencyLimit`（BLOCK，預設） | 等待 —— 20 個全部成功 | 2 |
| `@ConcurrencyLimit(policy = REJECT)` | `InvocationRejectedException` | ≤ 2 |
| Resilience4j `@Bulkhead`（`maxWaitDuration: 0`） | `BulkheadFullException` | ≤ 2 |

搭配 virtual threads 時，優先用 semaphore bulkhead 而非 `ThreadPoolBulkhead`：thread-pool bulkhead 會把工作搬回 platform thread 執行。

**選 Spring 內建**：只需要重試或併發上限，且不想多一個依賴。**選 Resilience4j**：需要斷路器、限流、fallback、指標/健康檢查，或集中管理的設定。

```bash
curl localhost:8080/example/spring-core/retry
seq 1 6 | xargs -P 6 -I{} sh -c 'echo "$(curl -s localhost:8080/example/spring-core/concurrency-reject)"'
seq 1 6 | xargs -P 6 -I{} sh -c 'echo "$(curl -s localhost:8080/example/spring-core/bulkhead)"'
```
