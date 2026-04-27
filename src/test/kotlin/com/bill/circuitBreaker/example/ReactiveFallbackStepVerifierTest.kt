package com.bill.circuitBreaker.example

import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

@SpringBootTest
class ReactiveFallbackStepVerifierTest {

    @Autowired
    lateinit var circuitBreakerRegistry: CircuitBreakerRegistry

    @BeforeEach
    fun setUp() {
        circuitBreakerRegistry.circuitBreaker("test").reset()
    }

    // When CB is OPEN, the operator immediately emits CallNotPermittedException
    // without subscribing to the upstream Mono at all.
    @Test
    fun `OPEN circuit breaker emits CallNotPermittedException`() {
        val cb = circuitBreakerRegistry.circuitBreaker("test").also { it.transitionToOpenState() }

        StepVerifier.create(
            Mono.just("data").transform(CircuitBreakerOperator.of(cb))
        )
            .expectError(CallNotPermittedException::class.java)
            .verify()
    }

    // onErrorReturn converts CB rejection into a fallback value — no exception propagates.
    @Test
    fun `fallback value returned when circuit is OPEN`() {
        val cb = circuitBreakerRegistry.circuitBreaker("test").also { it.transitionToOpenState() }

        StepVerifier.create(
            Mono.just("data")
                .transform(CircuitBreakerOperator.of(cb))
                .onErrorReturn(CallNotPermittedException::class.java, "fallback-value")
        )
            .expectNext("fallback-value")
            .verifyComplete()
    }

    // onErrorResume provides dynamic fallback (e.g., from another Mono).
    @Test
    fun `dynamic fallback Mono returned when circuit is OPEN`() {
        val cb = circuitBreakerRegistry.circuitBreaker("test").also { it.transitionToOpenState() }

        StepVerifier.create(
            Mono.just("data")
                .transform(CircuitBreakerOperator.of(cb))
                .onErrorResume(CallNotPermittedException::class.java) { ex ->
                    Mono.just("recovered: ${ex.javaClass.simpleName}")
                }
        )
            .expectNext("recovered: CallNotPermittedException")
            .verifyComplete()
    }

    // Successful call passes through the circuit breaker transparently.
    @Test
    fun `CLOSED circuit breaker passes value through unchanged`() {
        val cb = circuitBreakerRegistry.circuitBreaker("test")  // reset() ensures CLOSED

        StepVerifier.create(
            Mono.just("data").transform(CircuitBreakerOperator.of(cb))
        )
            .expectNext("data")
            .verifyComplete()
    }
}
