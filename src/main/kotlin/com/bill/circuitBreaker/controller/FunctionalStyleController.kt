package com.bill.circuitBreaker.controller

import com.bill.circuitBreaker.service.Service
import io.github.resilience4j.bulkhead.BulkheadFullException
import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.decorators.Decorators
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.ratelimiter.RequestNotPermitted
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator
import io.github.resilience4j.reactor.retry.RetryOperator
import io.github.resilience4j.reactor.timelimiter.TimeLimiterOperator
import io.github.resilience4j.retry.RetryRegistry
import io.github.resilience4j.timelimiter.TimeLimiterRegistry
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier

/**
 * @author Bill.Lin 2024/6/29
 */
@RestController
@RequestMapping(value = ["/functional"])
class FunctionalStyleController(
    @param:Qualifier("functionalService")
    private val businessBService: Service,
    circuitBreakerRegistry: CircuitBreakerRegistry,
    threadPoolBulkheadRegistry: ThreadPoolBulkheadRegistry,
    bulkheadRegistry: BulkheadRegistry,
    retryRegistry: RetryRegistry,
    rateLimiterRegistry: RateLimiterRegistry,
    timeLimiterRegistry: TimeLimiterRegistry
) {

    companion object {
        private const val FUNCTIONAL = "functional"
    }

    private val circuitBreaker = circuitBreakerRegistry.circuitBreaker(FUNCTIONAL)
    private val bulkhead = bulkheadRegistry.bulkhead(FUNCTIONAL)
    private val threadPoolBulkhead = threadPoolBulkheadRegistry.bulkhead(FUNCTIONAL)
    private val retry = retryRegistry.retry(FUNCTIONAL)
    private val rateLimiter = rateLimiterRegistry.rateLimiter(FUNCTIONAL)
    private val timeLimiter = timeLimiterRegistry.timeLimiter(FUNCTIONAL)
    private val scheduledExecutorService: ScheduledExecutorService = Executors.newScheduledThreadPool(3)

    @GetMapping("failure")
    fun failure(): String {
        return execute { businessBService.failure() }
    }

    @GetMapping("success")
    fun success(): String {
        return execute { businessBService.success() }
    }

    @GetMapping("successException")
    fun successException(): String {
        return execute { businessBService.successException() }
    }

    @GetMapping("ignore")
    fun ignore(): String {
        return Decorators.ofSupplier { businessBService.ignoreException() }
            .withCircuitBreaker(circuitBreaker)
            .withBulkhead(bulkhead).get()
    }

    @GetMapping("monoSuccess")
    fun monoSuccess(): Mono<String> {
        return execute(businessBService.monoSuccess())
    }

    @GetMapping("monoFailure")
    fun monoFailure(): Mono<String> {
        return execute(businessBService.monoFailure())
    }

    @GetMapping("fluxSuccess")
    fun fluxSuccess(): Flux<String> {
        return execute(businessBService.fluxSuccess())
    }

    @GetMapping("fluxFailure")
    fun fluxFailure(): Flux<String> {
        return execute(businessBService.fluxFailure())
    }

    @GetMapping("monoTimeout")
    fun monoTimeout(): Mono<String> {
        return executeWithFallback(businessBService.monoTimeout(), this::monoFallback)
    }

    @GetMapping("fluxTimeout")
    fun fluxTimeout(): Flux<String> {
        return executeWithFallback(businessBService.fluxTimeout(), this::fluxFallback)
    }

    @GetMapping("futureFailure")
    fun futureFailure(): CompletableFuture<String> {
        return executeAsync { businessBService.failure() }
    }

    @GetMapping("futureSuccess")
    fun futureSuccess(): CompletableFuture<String> {
        return executeAsync { businessBService.success() }
    }

    @GetMapping("futureTimeout")
    fun futureTimeout(): CompletableFuture<String> {
        return executeAsyncWithFallback(this::timeout, this::fallback)
    }

    @GetMapping("fallback")
    fun failureWithFallback(): String {
        return businessBService.failureWithFallback()
    }

    @GetMapping("rateLimited")
    fun rateLimited(): String {
        return Decorators.ofSupplier { businessBService.rateLimitedCall() }
            .withRateLimiter(rateLimiter)
            .withCircuitBreaker(circuitBreaker)
            .withBulkhead(bulkhead)
            .withFallback(listOf(RequestNotPermitted::class.java), this::fallback)
            .get()
    }

    @GetMapping("monoRateLimited")
    fun monoRateLimited(): Mono<String> {
        return businessBService.monoRateLimited()
            .transform(RateLimiterOperator.of(rateLimiter))
            .transform(CircuitBreakerOperator.of(circuitBreaker))
            .transform(BulkheadOperator.of(bulkhead))
    }

    private fun timeout(): String {
        try {
            Thread.sleep(10000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return ""
    }

    private fun <T : Any> execute(publisher: Mono<T>): Mono<T> {
        return publisher
            .transform(BulkheadOperator.of(bulkhead))
            .transform(CircuitBreakerOperator.of(circuitBreaker))
            .transform(RetryOperator.of(retry))
    }

    private fun <T : Any> execute(publisher: Flux<T>): Flux<T> {
        return publisher
            .transform(BulkheadOperator.of(bulkhead))
            .transform(CircuitBreakerOperator.of(circuitBreaker))
            .transform(RetryOperator.of(retry))
    }

    private fun <T : Any> executeWithFallback(publisher: Mono<T>, fallback: (Throwable) -> Mono<T>): Mono<T> {
        return publisher
            .transform(TimeLimiterOperator.of(timeLimiter))
            .transform(BulkheadOperator.of(bulkhead))
            .transform(CircuitBreakerOperator.of(circuitBreaker))
            .onErrorResume(TimeoutException::class.java, fallback)
            .onErrorResume(CallNotPermittedException::class.java, fallback)
            .onErrorResume(BulkheadFullException::class.java, fallback)
    }

    private fun <T : Any> executeWithFallback(publisher: Flux<T>, fallback: (Throwable) -> Flux<T>): Flux<T> {
        return publisher
            .transform(TimeLimiterOperator.of(timeLimiter))
            .transform(BulkheadOperator.of(bulkhead))
            .transform(CircuitBreakerOperator.of(circuitBreaker))
            .onErrorResume(TimeoutException::class.java, fallback)
            .onErrorResume(CallNotPermittedException::class.java, fallback)
            .onErrorResume(BulkheadFullException::class.java, fallback)
    }

    private fun <T> execute(supplier: Supplier<T>): T {
        return Decorators.ofSupplier(supplier)
            .withCircuitBreaker(circuitBreaker)
            .withBulkhead(bulkhead)
            .withRetry(retry)
            .get()
    }

    private fun <T> executeAsync(supplier: Supplier<T>): CompletableFuture<T> {
        return Decorators.ofSupplier(supplier)
            .withThreadPoolBulkhead(threadPoolBulkhead)
            .withTimeLimiter(timeLimiter, scheduledExecutorService)
            .withCircuitBreaker(circuitBreaker)
            .withRetry(retry, scheduledExecutorService)
            .get().toCompletableFuture()
    }

    private fun <T> executeAsyncWithFallback(
        supplier: Supplier<T>,
        fallback: (Throwable) -> T
    ): CompletableFuture<T> {
        return Decorators.ofSupplier(supplier)
            .withThreadPoolBulkhead(threadPoolBulkhead)
            .withTimeLimiter(timeLimiter, scheduledExecutorService)
            .withCircuitBreaker(circuitBreaker)
            .withFallback(
                listOf(
                    TimeoutException::class.java,
                    CallNotPermittedException::class.java,
                    BulkheadFullException::class.java
                ),
                fallback
            )
            .get().toCompletableFuture()
    }

    private fun fallback(ex: Throwable): String {
        return "Recovered: $ex"
    }

    private fun monoFallback(ex: Throwable): Mono<String> {
        return Mono.just("Recovered: $ex")
    }

    private fun fluxFallback(ex: Throwable): Flux<String> {
        return Flux.just("Recovered: $ex")
    }
}