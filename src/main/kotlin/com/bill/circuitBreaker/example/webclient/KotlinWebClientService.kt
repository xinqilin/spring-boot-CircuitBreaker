package com.bill.circuitBreaker.example.webclient

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator
import io.github.resilience4j.reactor.retry.RetryOperator
import io.github.resilience4j.retry.RetryRegistry
import io.github.resilience4j.timelimiter.annotation.TimeLimiter
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.io.IOException

@Component
class KotlinWebClientService(
    private val webClient: WebClient,
    circuitBreakerRegistry: CircuitBreakerRegistry,
    retryRegistry: RetryRegistry
) {
    companion object {
        private const val WEB_CLIENT = "webClient"
    }

    private val circuitBreaker = circuitBreakerRegistry.circuitBreaker(WEB_CLIENT)
    private val retry = retryRegistry.retry(WEB_CLIENT)

    // --- Annotation style ---

    @CircuitBreaker(name = WEB_CLIENT, fallbackMethod = "fallback")
    @TimeLimiter(name = WEB_CLIENT)
    fun callSuccess(): Mono<String> = webClient.get()
        .uri("/basic/success")
        .retrieve()
        .onStatus(HttpStatusCode::is5xxServerError) { Mono.error(IOException("Downstream 5xx")) }
        .bodyToMono(String::class.java)

    @CircuitBreaker(name = WEB_CLIENT, fallbackMethod = "fallback")
    fun callFailure(): Mono<String> = webClient.get()
        .uri("/basic/failure")
        .retrieve()
        .onStatus(HttpStatusCode::is5xxServerError) { Mono.error(IOException("Downstream 5xx")) }
        .bodyToMono(String::class.java)

    // --- Functional (Reactor operator) style ---
    // Same logic — circuit breaker and retry applied via .transform() chain.
    // Useful when you cannot add annotations (e.g., lambdas, third-party code).
    fun callSuccessFunctional(): Mono<String> = webClient.get()
        .uri("/basic/success")
        .retrieve()
        .onStatus(HttpStatusCode::is5xxServerError) { Mono.error(IOException("Downstream 5xx")) }
        .bodyToMono(String::class.java)
        .transform(CircuitBreakerOperator.of(circuitBreaker))
        .transform(RetryOperator.of(retry))

    @Suppress("unused")
    private fun fallback(ex: Exception): Mono<String> =
        Mono.just("WebClient fallback: ${ex.javaClass.simpleName} — ${ex.message}")
}
