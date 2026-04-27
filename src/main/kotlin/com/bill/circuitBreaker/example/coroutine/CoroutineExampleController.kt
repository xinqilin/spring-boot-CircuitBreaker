package com.bill.circuitBreaker.example.coroutine

import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// Spring MVC supports suspend controller methods via kotlinx-coroutines-reactor adapter.
// The coroutine runs on Dispatchers.Unconfined/servlet-thread pool — no blocking occurs.
@RestController
@RequestMapping("/example/coroutine")
class CoroutineExampleController(
    private val coroutineCbService: CoroutineCbService
) {

    @GetMapping("/success")
    suspend fun success(): String = coroutineCbService.success()

    @GetMapping("/failure")
    suspend fun failure(): String = try {
        coroutineCbService.failure()
    } catch (ex: Exception) {
        "Coroutine failed: ${ex.javaClass.simpleName} — ${ex.message}"
    }

    @GetMapping("/retry")
    suspend fun retry(): String = try {
        coroutineCbService.failureWithRetry()
    } catch (ex: Exception) {
        "Coroutine retry exhausted: ${ex.javaClass.simpleName}"
    }

    @GetMapping("/open")
    suspend fun open(): String = try {
        // Force circuit open for demonstration; real apps use failure accumulation
        coroutineCbService.failure()
    } catch (ex: CallNotPermittedException) {
        "Circuit is OPEN — ${ex.message}"
    } catch (ex: Exception) {
        "Other error: ${ex.message}"
    }
}
