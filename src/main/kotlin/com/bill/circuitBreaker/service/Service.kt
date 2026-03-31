package com.bill.circuitBreaker.service

import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.CompletableFuture


/**
 * @author Bill.Lin 2024/6/29
 */
interface Service {

    fun failure(): String

    fun failureWithFallback(): String

    fun success(): String

    fun successException(): String

    fun ignoreException(): String

    fun fluxSuccess(): Flux<String>

    fun fluxFailure(): Flux<String>

    fun fluxTimeout(): Flux<String>

    fun monoSuccess(): Mono<String>

    fun monoFailure(): Mono<String>

    fun monoTimeout(): Mono<String>

    fun futureSuccess(): CompletableFuture<String>

    fun futureFailure(): CompletableFuture<String>

    fun futureTimeout(): CompletableFuture<String>

    fun rateLimitedCall(): String

    fun monoRateLimited(): Mono<String>
}