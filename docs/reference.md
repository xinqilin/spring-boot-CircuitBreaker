# Endpoint, Configuration & Monitoring Reference

[Back to README](../README.md)

## Endpoint Reference

All endpoints respond to `GET`. Both `/basic/*` and `/functional/*` expose the same paths.

| Path (suffix) | Patterns Applied | Description |
|---|---|---|
| `success` | CB + Bulkhead + Retry | Returns success response |
| `failure` | CB + Bulkhead + Retry | Always throws `HttpServerErrorException` |
| `successException` | CB + Bulkhead | Throws `HttpClientErrorException` (4xx — counted as success by CB) |
| `ignore` | CB + Bulkhead | Throws `BusinessException` (counted as success by CB) |
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
| `rateLimited` | RateLimiter (basic 10/s, functional 6/500ms) + CB + Bulkhead | Rate-limited sync call |
| `monoRateLimited` | RateLimiter (basic 10/s, functional 6/500ms) + CB + Bulkhead | Rate-limited reactive call |

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
      timeoutDuration: 0               # reject at once, like basic

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
./gradlew bootRun     # the app runs on the host at :8080
docker compose up -d
```

This starts:
- **Prometheus** at http://localhost:9090 — scrapes the app at `host.docker.internal:8080/actuator/prometheus` every 5s; check http://localhost:9090/targets shows the `circuitbreaker` job as `UP`
- **Grafana** at http://localhost:3000 — default credentials: `admin` / `admin`; the Prometheus datasource is provisioned automatically (`docker/grafana/provisioning`)

The stack uses normal bridge networking (no `network_mode: host`), so it works the same on Docker Desktop, OrbStack and Linux.

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
