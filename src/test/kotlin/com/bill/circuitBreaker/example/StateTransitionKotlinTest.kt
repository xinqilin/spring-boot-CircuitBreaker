package com.bill.circuitBreaker.example

import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.io.IOException

@SpringBootTest
class StateTransitionKotlinTest {

    @Autowired
    lateinit var circuitBreakerRegistry: CircuitBreakerRegistry

    private lateinit var cb: CircuitBreaker

    @BeforeEach
    fun setUp() {
        cb = circuitBreakerRegistry.circuitBreaker("test")
        cb.reset()  // transition to CLOSED + clear sliding window
    }

    // Scenario 1: force transition directly — useful for testing fallback logic in isolation
    @Test
    fun `forced transition to OPEN rejects subsequent calls`() {
        cb.transitionToOpenState()

        assertThat(cb.state).isEqualTo(CircuitBreaker.State.OPEN)

        assertThatThrownBy {
            CircuitBreaker.decorateSupplier(cb) { "unreachable" }.get()
        }.isInstanceOf(CallNotPermittedException::class.java)
    }

    // Scenario 2: burst failures drive CLOSED → OPEN naturally.
    // test instance: slidingWindowSize=5, minimumNumberOfCalls=5, failureRateThreshold=60%
    // 5 IOExceptions = 100% failure rate → exceeds 60% threshold → OPEN
    @Test
    fun `burst failures drive CB from CLOSED to OPEN`() {
        repeat(5) {
            runCatching {
                cb.executeCheckedSupplier { throw IOException("simulated failure") }
            }
        }

        assertThat(cb.state).isEqualTo(CircuitBreaker.State.OPEN)
    }

    // Scenario 3: verify HALF_OPEN allows limited calls and transitions correctly
    @Test
    fun `HALF_OPEN allows permitted calls then transitions to CLOSED on success`() {
        cb.transitionToOpenState()
        cb.transitionToHalfOpenState()

        assertThat(cb.state).isEqualTo(CircuitBreaker.State.HALF_OPEN)

        // test instance: permittedNumberOfCallsInHalfOpenState=2 — both succeed → CLOSED
        repeat(2) {
            CircuitBreaker.decorateSupplier(cb) { "ok" }.get()
        }

        assertThat(cb.state).isEqualTo(CircuitBreaker.State.CLOSED)
    }

    // Scenario 4: verify metrics counters
    @Test
    fun `metrics reflect call outcomes accurately`() {
        CircuitBreaker.decorateSupplier(cb) { "ok" }.get()
        runCatching { cb.executeCheckedSupplier { throw IOException("fail") } }

        val metrics = cb.metrics
        assertThat(metrics.numberOfSuccessfulCalls).isEqualTo(1)
        assertThat(metrics.numberOfFailedCalls).isEqualTo(1)
    }
}
