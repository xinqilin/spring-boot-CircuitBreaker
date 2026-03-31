package com.bill.circuitBreaker.config

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer
import io.github.resilience4j.core.registry.EntryAddedEvent
import io.github.resilience4j.core.registry.EntryRemovedEvent
import io.github.resilience4j.core.registry.EntryReplacedEvent
import io.github.resilience4j.core.registry.RegistryEventConsumer
import io.github.resilience4j.retry.Retry
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.server.RequestPredicates.GET
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions.route
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.ServerResponse.permanentRedirect
import java.net.URI


/**
 * @author Bill.Lin 2024/6/29
 */
@Configuration
class ApplicationConfig {

    companion object {
        private val log = LoggerFactory.getLogger(ApplicationConfig::class.java)
    }

    @Bean
    fun redirectRoot(): RouterFunction<ServerResponse> {
        return route(
            GET("/")
        ) { req -> permanentRedirect(URI.create("/actuator")).build() }
    }

    @Bean
    fun testCustomizer(): CircuitBreakerConfigCustomizer {
        return CircuitBreakerConfigCustomizer
            .of("basic") { builder: CircuitBreakerConfig.Builder ->
                builder.slidingWindowSize(
                    100
                )
            }
    }

    @Bean
    fun myRegistryEventConsumer(): RegistryEventConsumer<CircuitBreaker> {
        return object : RegistryEventConsumer<CircuitBreaker> {
            override fun onEntryAddedEvent(entryAddedEvent: EntryAddedEvent<CircuitBreaker>) {
                entryAddedEvent.getAddedEntry().getEventPublisher().onEvent { event -> log.info(event.toString()) }
            }

            override fun onEntryRemovedEvent(entryRemoveEvent: EntryRemovedEvent<CircuitBreaker>) {
            }

            override fun onEntryReplacedEvent(entryReplacedEvent: EntryReplacedEvent<CircuitBreaker>) {
            }
        }
    }

    @Bean
    fun myRetryRegistryEventConsumer(): RegistryEventConsumer<Retry> {
        return object : RegistryEventConsumer<Retry> {
            override fun onEntryAddedEvent(entryAddedEvent: EntryAddedEvent<Retry>) {
                entryAddedEvent.getAddedEntry().getEventPublisher().onEvent { event -> log.info(event.toString()) }
            }

            override fun onEntryRemovedEvent(entryRemoveEvent: EntryRemovedEvent<Retry>) {
            }

            override fun onEntryReplacedEvent(entryReplacedEvent: EntryReplacedEvent<Retry>) {
            }
        }
    }
}