package com.bill.circuitBreaker.controller

import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.retry.RetryRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
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

    // basic: 10 permits per 1s with timeoutDuration=0, so a burst beyond the limit is rejected at once.
    @Test
    fun `basic rate limiter returns fallback when the burst exceeds the limit`() {
        val bodies = (1..25).map {
            mvc.get().uri("/basic/rateLimited").exchange().response.contentAsString
        }

        assertThat(bodies).anyMatch { it.startsWith("Rate limit exceeded") }
    }

    private fun cb(instance: String): CircuitBreaker = circuitBreakerRegistry.circuitBreaker(instance)
}
