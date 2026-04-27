package com.bill.circuitBreaker.example.webclient;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.decorators.Decorators;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component("javaRestClientService")
public class JavaRestClientService {

    private static final String REST_CLIENT = "restClient";

    public record DownstreamResponse(String body, String source) {}

    private final RestClient restClient;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public JavaRestClientService(RestClient restClient, CircuitBreakerRegistry circuitBreakerRegistry) {
        this.restClient = restClient;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    // --- Annotation style ---
    // RestClient throws HttpServerErrorException for 5xx by default — matches recordExceptions.

    @CircuitBreaker(name = REST_CLIENT, fallbackMethod = "fallback")
    public DownstreamResponse callSuccess() {
        var body = restClient.get()
            .uri("/basic/success")
            .retrieve()
            .body(String.class);
        return new DownstreamResponse(body, "restClient-annotation");
    }

    @CircuitBreaker(name = REST_CLIENT, fallbackMethod = "fallback")
    public DownstreamResponse callFailure() {
        var body = restClient.get()
            .uri("/basic/failure")
            .retrieve()
            .body(String.class);
        return new DownstreamResponse(body, "restClient-annotation");
    }

    // --- Functional (Decorators.ofSupplier) style ---
    // Equivalent to annotation style; useful when annotation cannot be applied.
    public DownstreamResponse callSuccessFunctional() {
        var cb = circuitBreakerRegistry.circuitBreaker(REST_CLIENT);
        return Decorators.<DownstreamResponse>ofSupplier(() -> {
            var body = restClient.get()
                .uri("/basic/success")
                .retrieve()
                .body(String.class);
            return new DownstreamResponse(body, "restClient-functional");
        })
        .withCircuitBreaker(cb)
        .withFallback(List.of(Exception.class), this::fallback)
        .get();
    }

    // Java 21 pattern-matched switch — dispatches fallback message by exception type.
    // Parameter is Throwable (required by Decorators.withFallback and @CircuitBreaker annotation).
    private DownstreamResponse fallback(Throwable ex) {
        var message = switch (ex) {
            case HttpServerErrorException e -> "Downstream 5xx: " + e.getStatusCode();
            case CallNotPermittedException e -> "Circuit open — call rejected";
            default -> "Fallback: " + ex.getMessage();
        };
        return new DownstreamResponse(message, "fallback");
    }
}
