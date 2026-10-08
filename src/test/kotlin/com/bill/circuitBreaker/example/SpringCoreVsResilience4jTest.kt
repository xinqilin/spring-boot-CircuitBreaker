package com.bill.circuitBreaker.example

import com.bill.circuitBreaker.example.springcore.SpringCoreResilienceService
import io.github.resilience4j.bulkhead.BulkheadFullException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.resilience.InvocationRejectedException
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors

// Virtual threads have no pool size to act as an implicit limit, so concurrency must be capped explicitly.
// Each burst fires 20 calls at once on virtual threads against a limit of 2.
@SpringBootTest
class SpringCoreVsResilience4jTest {

    @Autowired
    lateinit var service: SpringCoreResilienceService

    @BeforeEach
    fun setUp() {
        service.reset()
    }

    @Test
    fun `@Retryable makes 3 attempts then rethrows the last exception`() {
        assertThatThrownBy { service.alwaysFails() }.isInstanceOf(IOException::class.java)

        assertThat(service.attempts.get()).isEqualTo(3)
    }

    @Test
    fun `@ConcurrencyLimit BLOCK throttles - every call succeeds, never more than 2 at once`() {
        val results = burst { service.limitedBlock() }

        assertThat(results).allMatch { it.isSuccess }
        assertThat(service.peakConcurrency.get()).isEqualTo(2)
    }

    @Test
    fun `@ConcurrencyLimit REJECT fails fast with InvocationRejectedException`() {
        val results = burst { service.limitedReject() }

        assertThat(results).anyMatch { it.exceptionOrNull() is InvocationRejectedException }
        assertThat(service.peakConcurrency.get()).isLessThanOrEqualTo(2)
    }

    @Test
    fun `Resilience4j semaphore bulkhead fails fast with BulkheadFullException`() {
        val results = burst { service.bulkhead() }

        assertThat(results).anyMatch { it.exceptionOrNull() is BulkheadFullException }
        assertThat(service.peakConcurrency.get()).isLessThanOrEqualTo(2)
    }

    private fun burst(call: () -> String): List<Result<String>> =
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            (1..20).map { executor.submit(Callable { runCatching(call) }) }.map { it.get() }
        }
}
