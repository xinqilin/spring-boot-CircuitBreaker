package com.bill.circuitBreaker.service.impl

import com.bill.circuitBreaker.exception.BusinessException
import com.bill.circuitBreaker.service.Service
import io.github.resilience4j.bulkhead.BulkheadFullException
import io.github.resilience4j.bulkhead.annotation.Bulkhead
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.ratelimiter.RequestNotPermitted
import io.github.resilience4j.ratelimiter.annotation.RateLimiter
import io.github.resilience4j.retry.annotation.Retry
import io.github.resilience4j.timelimiter.annotation.TimeLimiter
import io.vavr.control.Try
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException


/**
 * @author Bill.Lin 2024/6/29
 */
@Component(value = "basicService")
class BasicService : Service {

    companion object {
        private const val BASIC = "basic"
    }

    @CircuitBreaker(name = BASIC)
    @Bulkhead(name = BASIC)
    @Retry(name = BASIC)
    override fun failure(): String {
        throw HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "This is a remote exception")
    }

    @CircuitBreaker(name = BASIC)
    @Bulkhead(name = BASIC)
    override fun ignoreException(): String {
        throw BusinessException("This exception is ignored by the CircuitBreaker of backend basic")
    }

    @CircuitBreaker(name = BASIC)
    @Bulkhead(name = BASIC)
    @Retry(name = BASIC)
    override fun success(): String {
        return "Hello World from backend basic"
    }

    @CircuitBreaker(name = BASIC)
    @Bulkhead(name = BASIC)
    override fun successException(): String {
        throw HttpClientErrorException(HttpStatus.BAD_REQUEST, "This is a remote client exception")
    }

    @CircuitBreaker(name = BASIC)
    @Bulkhead(name = BASIC)
    @Retry(name = BASIC)
    override fun fluxFailure(): Flux<String> {
        return Flux.error(IOException("BAM!"))
    }

    @TimeLimiter(name = BASIC)
    @CircuitBreaker(name = BASIC, fallbackMethod = "fluxFallback")
    override fun fluxTimeout(): Flux<String> {
        return Flux.just("Hello World from backend basic")
            .delayElements(Duration.ofSeconds(10))
    }

    @TimeLimiter(name = BASIC)
    @CircuitBreaker(name = BASIC)
    @Bulkhead(name = BASIC)
    @Retry(name = BASIC)
    override fun monoSuccess(): Mono<String> {
        return Mono.just("Hello World from backend basic")
    }

    @CircuitBreaker(name = BASIC)
    @Bulkhead(name = BASIC)
    @Retry(name = BASIC)
    override fun monoFailure(): Mono<String> {
        return Mono.error(IOException("BAM!"))
    }

    @TimeLimiter(name = BASIC)
    @Bulkhead(name = BASIC)
    @CircuitBreaker(name = BASIC, fallbackMethod = "monoFallback")
    override fun monoTimeout(): Mono<String> {
        return Mono.just("Hello World from backend basic")
            .delayElement(Duration.ofSeconds(10))
    }

    @TimeLimiter(name = BASIC)
    @CircuitBreaker(name = BASIC)
    @Retry(name = BASIC)
    override fun fluxSuccess(): Flux<String> {
        return Flux.just("Hello", "World")
    }

    @CircuitBreaker(name = BASIC, fallbackMethod = "fallback")
    override fun failureWithFallback(): String {
        return failure()
    }

    @Bulkhead(name = BASIC, type = Bulkhead.Type.THREADPOOL)
    @TimeLimiter(name = BASIC)
    @CircuitBreaker(name = BASIC)
    @Retry(name = BASIC)
    override fun futureSuccess(): CompletableFuture<String> {
        return CompletableFuture.completedFuture("Hello World from backend basic")
    }

    @Bulkhead(name = BASIC, type = Bulkhead.Type.THREADPOOL)
    @TimeLimiter(name = BASIC)
    @CircuitBreaker(name = BASIC)
    @Retry(name = BASIC)
    override fun futureFailure(): CompletableFuture<String> {
        val future = CompletableFuture<String>()
        future.completeExceptionally(IOException("BAM!"))
        return future
    }

    @Bulkhead(name = BASIC, type = Bulkhead.Type.THREADPOOL)
    @TimeLimiter(name = BASIC)
    @CircuitBreaker(name = BASIC, fallbackMethod = "futureFallback")
    override fun futureTimeout(): CompletableFuture<String> {
        Try.run { Thread.sleep(5000) }
        return CompletableFuture.completedFuture("Hello World from backend basic")
    }

    private fun fallback(ex: HttpServerErrorException): String {
        return "Recovered HttpServerErrorException: ${ex.message}"
    }

    private fun fallback(ex: Exception): String {
        return "Recovered: $ex"
    }

    private fun futureFallback(ex: TimeoutException): CompletableFuture<String> {
        return CompletableFuture.completedFuture("Recovered specific TimeoutException: $ex")
    }

    private fun futureFallback(ex: BulkheadFullException): CompletableFuture<String> {
        return CompletableFuture.completedFuture("Recovered specific BulkheadFullException: $ex")
    }

    private fun futureFallback(ex: CallNotPermittedException): CompletableFuture<String> {
        return CompletableFuture.completedFuture("Recovered specific CallNotPermittedException: $ex")
    }

    private fun monoFallback(ex: Exception): Mono<String> {
        return Mono.just("Recovered: $ex")
    }

    private fun fluxFallback(ex: Exception): Flux<String> {
        return Flux.just("Recovered: $ex")
    }

    @RateLimiter(name = BASIC, fallbackMethod = "rateLimitFallback")
    @CircuitBreaker(name = BASIC)
    override fun rateLimitedCall(): String {
        return "Hello World from rate-limited backend basic"
    }

    @RateLimiter(name = BASIC, fallbackMethod = "monoRateLimitFallback")
    @CircuitBreaker(name = BASIC)
    override fun monoRateLimited(): Mono<String> {
        return Mono.just("Hello World from rate-limited backend basic")
    }

    private fun rateLimitFallback(ex: RequestNotPermitted): String {
        return "Rate limit exceeded: ${ex.message}"
    }

    private fun monoRateLimitFallback(ex: RequestNotPermitted): Mono<String> {
        return Mono.just("Rate limit exceeded: ${ex.message}")
    }
}