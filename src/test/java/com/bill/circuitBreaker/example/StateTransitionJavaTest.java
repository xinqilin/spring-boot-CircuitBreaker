package com.bill.circuitBreaker.example;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class StateTransitionJavaTest {

    @Autowired
    CircuitBreakerRegistry circuitBreakerRegistry;

    private CircuitBreaker cb;

    @BeforeEach
    void setUp() {
        cb = circuitBreakerRegistry.circuitBreaker("test");
        cb.reset();
    }

    // Scenario 1: forced transition using Java 21 var + switch pattern matching
    @Test
    void forcedTransitionToOpenRejectsSubsequentCalls() {
        cb.transitionToOpenState();

        var state = cb.getState();
        // Java 21 pattern-matched switch over enum
        var description = switch (state) {
            case OPEN -> "Circuit is OPEN — calls rejected";
            case CLOSED -> "Circuit is CLOSED — calls allowed";
            case HALF_OPEN -> "Circuit is HALF_OPEN — limited calls allowed";
            default -> "Unknown state: " + state;
        };

        assertThat(description).startsWith("Circuit is OPEN");
        assertThatThrownBy(() -> CircuitBreaker.decorateSupplier(cb, () -> "unreachable").get())
            .isInstanceOf(CallNotPermittedException.class);
    }

    // Scenario 2: burst failures drive OPEN naturally
    @Test
    void burstFailuresDriveClosedToOpen() throws Exception {
        for (int i = 0; i < 5; i++) {
            try {
                cb.<String>executeCheckedSupplier(() -> {
                    throw new IOException("simulated failure");
                });
            } catch (Throwable ignored) {}
        }

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    // Scenario 3: HALF_OPEN → CLOSED on successful probe calls
    @Test
    void halfOpenTransitionsToClosedOnSuccess() {
        cb.transitionToOpenState();
        cb.transitionToHalfOpenState();

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        for (int i = 0; i < 2; i++) {
            CircuitBreaker.decorateSupplier(cb, () -> "ok").get();
        }

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // Scenario 4: metrics counters with Java record for assertion readability
    @Test
    void metricsReflectCallOutcomes() throws Exception {
        CircuitBreaker.decorateSupplier(cb, () -> "ok").get();
        try {
            cb.<String>executeCheckedSupplier(() -> {
                throw new IOException("fail");
            });
        } catch (Throwable ignored) {}

        record Counts(int success, int failure) {}
        var m = cb.getMetrics();
        var counts = new Counts(m.getNumberOfSuccessfulCalls(), m.getNumberOfFailedCalls());

        assertThat(counts.success()).isEqualTo(1);
        assertThat(counts.failure()).isEqualTo(1);
    }
}
