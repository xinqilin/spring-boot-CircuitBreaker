package com.bill.circuitBreaker.example.coroutine

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.kotlin.circuitbreaker.executeSuspendFunction
import io.github.resilience4j.kotlin.retry.executeSuspendFunction
import io.github.resilience4j.retry.RetryRegistry
import org.springframework.stereotype.Component
import java.io.IOException

@Component
class CoroutineCbService(
    circuitBreakerRegistry: CircuitBreakerRegistry,
    retryRegistry: RetryRegistry
) {
    private val circuitBreaker = circuitBreakerRegistry.circuitBreaker("coroutine")
    private val retry = retryRegistry.retry("coroutine")

    // Circuit breaker wraps a suspend lambda — coroutine is NOT blocked.
    suspend fun success(): String = circuitBreaker.executeSuspendFunction {
        "Hello from coroutine circuit breaker"
    }

    // This failure drives the CB failure counter on each call.
    suspend fun failure(): String = circuitBreaker.executeSuspendFunction {
        throw IOException("Simulated coroutine downstream failure")
    }

    // Combining CB + Retry: retry wraps the circuit breaker call.
    // On each retry attempt the CB is checked again — if it opens mid-retry the attempt is rejected.
    suspend fun failureWithRetry(): String = retry.executeSuspendFunction {
        circuitBreaker.executeSuspendFunction {
            throw IOException("Simulated failure — will retry")
        }
    }
}
