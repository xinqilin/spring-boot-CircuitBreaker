# 端點、設定與監控參考

[回到 README](../README.zh-TW.md)

## 端點參考

所有端點皆為 `GET`。`/basic/*` 和 `/functional/*` 暴露完全相同的路徑。

| 路徑（後綴） | 套用模式 | 說明 |
|---|---|---|
| `success` | CB + Bulkhead + Retry | 回傳成功回應 |
| `failure` | CB + Bulkhead + Retry | 永遠拋出 `HttpServerErrorException` |
| `successException` | CB + Bulkhead | 拋出 `HttpClientErrorException`（4xx，CB 計為成功） |
| `ignore` | CB + Bulkhead | 拋出 `BusinessException`（CB 計為成功） |
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
| `rateLimited` | RateLimiter（basic 10/s、functional 6/500ms）+ CB + Bulkhead | 受速率限制的同步呼叫 |
| `monoRateLimited` | RateLimiter（basic 10/s、functional 6/500ms）+ CB + Bulkhead | 受速率限制的響應式呼叫 |

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
      timeoutDuration: 0               # 與 basic 一樣立即拒絕

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
