package com.bill.circuitBreaker.example.springcore

import io.github.resilience4j.bulkhead.annotation.Bulkhead
import org.springframework.resilience.annotation.ConcurrencyLimit
import org.springframework.resilience.annotation.Retryable
import org.springframework.stereotype.Component
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

// Spring Framework 7 core resilience (@Retryable, @ConcurrencyLimit) next to the Resilience4j equivalent.
// Requires @EnableResilientMethods (see SpringCoreResilienceConfig) — Spring Boot does not enable it automatically.
@Component
class SpringCoreResilienceService {

    val attempts = AtomicInteger()
    val peakConcurrency = AtomicInteger()
    private val inFlight = AtomicInteger()

    fun reset() {
        attempts.set(0)
        peakConcurrency.set(0)
    }

    // 1 initial attempt + 2 retries = 3 attempts, same as Resilience4j `maxAttempts: 3`.
    // No circuit breaker and no fallback: the last exception reaches the caller.
    // @Throws is required: without it the CGLIB proxy wraps the checked IOException in UndeclaredThrowableException.
    @Retryable(includes = [IOException::class], maxRetries = 2, delay = 100)
    @Throws(IOException::class)
    fun alwaysFails(): String {
        attempts.incrementAndGet()
        throw IOException("Simulated downstream failure")
    }

    // BLOCK (default): excess callers wait for a permit — throttling, nothing is rejected.
    @ConcurrencyLimit(limit = 2)
    fun limitedBlock(): String = tracked()

    // REJECT: excess callers fail immediately with InvocationRejectedException.
    @ConcurrencyLimit(limit = 2, policy = ConcurrencyLimit.ThrottlePolicy.REJECT)
    fun limitedReject(): String = tracked()

    // Resilience4j semaphore bulkhead with maxWaitDuration=0: excess callers get BulkheadFullException.
    @Bulkhead(name = "springCore")
    fun bulkhead(): String = tracked()

    private fun tracked(): String {
        peakConcurrency.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
        try {
            Thread.sleep(200)
            return "ok"
        } finally {
            inFlight.decrementAndGet()
        }
    }
}
