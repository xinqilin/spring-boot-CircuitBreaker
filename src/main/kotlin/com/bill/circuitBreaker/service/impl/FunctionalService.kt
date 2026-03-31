package com.bill.circuitBreaker.service.impl

import com.bill.circuitBreaker.exception.BusinessException
import com.bill.circuitBreaker.service.Service
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


/**
 * @author Bill.Lin 2024/6/29
 */
@Component(value = "functionalService")
class FunctionalService : Service {

    companion object {
        private const val FUNCTIONAL = "functional"
    }

    override fun failure(): String {
        throw HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "This is a remote exception")
    }

    override fun success(): String {
        return "Hello World from backend functional"
    }

    override fun successException(): String {
        throw HttpClientErrorException(HttpStatus.BAD_REQUEST, "This is a remote client exception")
    }

    override fun ignoreException(): String {
        throw BusinessException("This exception is ignored by the CircuitBreaker of backend functional")
    }

    override fun fluxFailure(): Flux<String> {
        return Flux.error(IOException("BAM!"))
    }

    override fun fluxTimeout(): Flux<String> {
        return Flux.just<String>("Hello World from backend functional")
            .delayElements(Duration.ofSeconds(10))
    }

    override fun monoSuccess(): Mono<String> {
        return Mono.just("Hello World from backend functional")
    }

    override fun monoFailure(): Mono<String> {
        return Mono.error(IOException("BAM!"))
    }

    override fun monoTimeout(): Mono<String> {
        return Mono.just<String>("Hello World from backend functional")
            .delayElement(Duration.ofSeconds(10))
    }

    override fun fluxSuccess(): Flux<String> {
        return Flux.just("Hello", "World")
    }

    override fun futureSuccess(): CompletableFuture<String> {
        return CompletableFuture.completedFuture("Hello World from backend functional")
    }

    override fun futureFailure(): CompletableFuture<String> {
        val future = CompletableFuture<String>()
        future.completeExceptionally(IOException("BAM!"))
        return future
    }

    override fun futureTimeout(): CompletableFuture<String> {
        Try.run { Thread.sleep(5000) }
        return CompletableFuture.completedFuture("Hello World from backend functional")
    }

    override fun failureWithFallback(): String {
        return Try.ofSupplier(this::failure).recover { ex: Throwable ->
            this.fallback(
                ex
            )
        }.get()
    }

    private fun fallback(ex: Throwable): String {
        return "Recovered: $ex"
    }

    override fun rateLimitedCall(): String {
        return "Hello World from rate-limited backend functional"
    }

    override fun monoRateLimited(): Mono<String> {
        return Mono.just("Hello World from rate-limited backend functional")
    }
}