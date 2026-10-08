package com.bill.circuitBreaker.controller

import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.retry.RetryRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.web.context.WebApplicationContext

// Runs the same scenarios against /basic (annotations) and /functional (Decorators / Reactor operators)
// to prove both styles behave identically. Each path prefix is also the Resilience4j instance name.
@SpringBootTest
class ResilienceEndpointsTest {

    @Autowired
    lateinit var wac: WebApplicationContext

    @Autowired
    lateinit var circuitBreakerRegistry: CircuitBreakerRegistry

    @Autowired
    lateinit var retryRegistry: RetryRegistry

    private lateinit var mvc: MockMvcTester

    @BeforeEach
    fun setUp() {
        mvc = MockMvcTester.from(wac)
        listOf("basic", "functional").forEach { circuitBreakerRegistry.circuitBreaker(it).reset() }
    }

    // Retry wraps CircuitBreaker in both styles, so every attempt is recorded by the circuit breaker.
    @ParameterizedTest
    @ValueSource(strings = ["basic", "functional"])
    fun `5xx failure is retried 3 times and each attempt is recorded`(instance: String) {
        val retryMetrics = retryRegistry.retry(instance).metrics
        val retriedBefore = retryMetrics.numberOfFailedCallsWithRetryAttempt

        mvc.get().uri("/$instance/failure").exchange()

        assertThat(retryMetrics.numberOfFailedCallsWithRetryAttempt - retriedBefore).isEqualTo(1)
        assertThat(cb(instance).metrics.numberOfFailedCalls).isEqualTo(3)
    }

    @ParameterizedTest
    @ValueSource(strings = ["basic", "functional"])
    fun `4xx client error is counted as success, not failure`(instance: String) {
        mvc.get().uri("/$instance/successException").exchange()

        assertThat(cb(instance).metrics.numberOfFailedCalls).isZero()
        assertThat(cb(instance).metrics.numberOfSuccessfulCalls).isEqualTo(1)
    }

    @ParameterizedTest
    @ValueSource(strings = ["basic", "functional"])
    fun `BusinessException is counted as success, not failure`(instance: String) {
        mvc.get().uri("/$instance/ignore").exchange()

        assertThat(cb(instance).metrics.numberOfFailedCalls).isZero()
        assertThat(cb(instance).metrics.numberOfSuccessfulCalls).isEqualTo(1)
    }

    @ParameterizedTest
    @ValueSource(strings = ["basic", "functional"])
    fun `OPEN circuit rejects calls with CallNotPermittedException`(instance: String) {
        cb(instance).transitionToOpenState()

        assertThat(mvc.get().uri("/$instance/failure"))
            .hasFailed()
            .failure().hasRootCauseInstanceOf(CallNotPermittedException::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["basic", "functional"])
    fun `OPEN circuit on monoTimeout returns fallback instead of error`(instance: String) {
        cb(instance).transitionToOpenState()

        assertThat(mvc.get().uri("/$instance/monoTimeout"))
            .hasStatusOk()
            .bodyText().startsWith("Recovered:").contains("CallNotPermittedException")
    }

    @ParameterizedTest
    @ValueSource(strings = ["basic", "functional"])
    fun `slow Mono times out after 2s and returns fallback`(instance: String) {
        assertThat(mvc.get().uri("/$instance/monoTimeout"))
            .hasStatusOk()
            .bodyText().startsWith("Recovered:").contains("TimeoutException")
    }

    // basic: minimumNumberOfCalls=5. Request 1 records 3 failures; request 2 records 2 more,
    // the circuit opens, and the third attempt is rejected — two requests are enough to open it.
    @Test
    fun `retry amplification opens the basic circuit after two requests`() {
        repeat(2) { mvc.get().uri("/basic/failure").exchange() }

        assertThat(cb("basic").state).isEqualTo(CircuitBreaker.State.OPEN)
    }

    // Every /basic/* endpoint shares the 'basic' instance: an OPEN circuit also blocks unrelated endpoints.
    // CircuitBreaker wraps RateLimiter, so the RequestNotPermitted fallback never gets a chance to run.
    @Test
    fun `OPEN basic circuit also rejects the rate-limited endpoint sharing the instance`() {
        cb("basic").transitionToOpenState()

        assertThat(mvc.get().uri("/basic/rateLimited"))
            .hasFailed()
            .failure().hasRootCauseInstanceOf(CallNotPermittedException::class.java)
    }

    // futureFallback is overloaded for TimeoutException / BulkheadFullException / CallNotPermittedException;
    // the most specific match wins.
    @Test
    fun `overloaded fallback picks the most specific exception type`() {
        cb("basic").transitionToOpenState()

        assertThat(mvc.get().uri("/basic/futureTimeout"))
            .hasStatusOk()
            .bodyText().startsWith("Recovered specific CallNotPermittedException")
    }

    // basic: 10 permits/1s, functional: 6 permits/500ms — both with timeoutDuration=0, so a burst beyond
    // the limit is rejected at once. Rejections are the limiter protecting us, not a downstream failure,
    // so they must not be recorded by the circuit breaker that wraps the rate limiter.
    @ParameterizedTest
    @CsvSource("basic,rateLimited", "basic,monoRateLimited", "functional,rateLimited", "functional,monoRateLimited")
    fun `burst beyond the rate limit returns fallback without tripping the circuit`(instance: String, endpoint: String) {
        val bodies = (1..25).map {
            mvc.get().uri("/$instance/$endpoint").exchange().response.contentAsString
        }

        assertThat(bodies).anyMatch { it.startsWith("Rate limit exceeded") }
        assertThat(cb(instance).metrics.numberOfFailedCalls).isZero()
    }

    private fun cb(instance: String): CircuitBreaker = circuitBreakerRegistry.circuitBreaker(instance)
}
