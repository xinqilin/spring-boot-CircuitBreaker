package com.bill.circuitBreaker.example.springcore

import io.github.resilience4j.bulkhead.BulkheadFullException
import org.springframework.resilience.InvocationRejectedException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// Try with concurrent callers, e.g.:
//   seq 1 6 | xargs -P 6 -I{} sh -c 'echo "$(curl -s localhost:8080/example/spring-core/concurrency-reject)"'
@RestController
@RequestMapping("/example/spring-core")
class SpringCoreExampleController(
    private val service: SpringCoreResilienceService
) {

    @GetMapping("/retry")
    fun retry(): String {
        val before = service.attempts.get()
        return try {
            service.alwaysFails()
        } catch (ex: Exception) {
            "Gave up after ${service.attempts.get() - before} attempts: ${ex.message}"
        }
    }

    @GetMapping("/concurrency-block")
    fun concurrencyBlock(): String = service.limitedBlock()

    @GetMapping("/concurrency-reject")
    fun concurrencyReject(): String = try {
        service.limitedReject()
    } catch (ex: InvocationRejectedException) {
        "Rejected by @ConcurrencyLimit"
    }

    @GetMapping("/bulkhead")
    fun bulkhead(): String = try {
        service.bulkhead()
    } catch (ex: BulkheadFullException) {
        "Rejected by Resilience4j bulkhead"
    }
}
