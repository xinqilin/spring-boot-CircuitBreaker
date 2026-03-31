package com.bill.circuitBreaker.controller

import com.bill.circuitBreaker.service.Service
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.CompletableFuture


/**
 * @author Bill.Lin 2024/6/29
 */
@RestController
@RequestMapping(value = ["/basic"])
class BasicController(
    @param:Qualifier("basicService")
    private val basicService: Service
) {

    @GetMapping("failure")
    fun failure(): String {
        return basicService.failure()
    }

    @GetMapping("success")
    fun success(): String {
        return basicService.success()
    }

    @GetMapping("successException")
    fun successException(): String {
        return basicService.successException()
    }

    @GetMapping("ignore")
    fun ignore(): String {
        return basicService.ignoreException()
    }

    @GetMapping("monoSuccess")
    fun monoSuccess(): Mono<String> {
        return basicService.monoSuccess()
    }

    @GetMapping("monoFailure")
    fun monoFailure(): Mono<String> {
        return basicService.monoFailure()
    }

    @GetMapping("fluxSuccess")
    fun fluxSuccess(): Flux<String> {
        return basicService.fluxSuccess()
    }

    @GetMapping("monoTimeout")
    fun monoTimeout(): Mono<String> {
        return basicService.monoTimeout()
    }

    @GetMapping("fluxTimeout")
    fun fluxTimeout(): Flux<String> {
        return basicService.fluxTimeout()
    }

    @GetMapping("futureFailure")
    fun futureFailure(): CompletableFuture<String> {
        return basicService.futureFailure()
    }

    @GetMapping("futureSuccess")
    fun futureSuccess(): CompletableFuture<String> {
        return basicService.futureSuccess()
    }

    @GetMapping("futureTimeout")
    fun futureTimeout(): CompletableFuture<String> {
        return basicService.futureTimeout()
    }

    @GetMapping("fluxFailure")
    fun fluxFailure(): Flux<String> {
        return basicService.fluxFailure()
    }

    @GetMapping("fallback")
    fun failureWithFallback(): String {
        return basicService.failureWithFallback()
    }

    @GetMapping("rateLimited")
    fun rateLimited(): String {
        return basicService.rateLimitedCall()
    }

    @GetMapping("monoRateLimited")
    fun monoRateLimited(): Mono<String> {
        return basicService.monoRateLimited()
    }
}