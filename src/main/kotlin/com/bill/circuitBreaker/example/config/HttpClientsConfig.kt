package com.bill.circuitBreaker.example.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import org.springframework.web.reactive.function.client.WebClient

@Configuration
class HttpClientsConfig {

    // Self-referencing base URL — calls this project's own /basic/* endpoints as demo downstream.
    // In production: replace with actual downstream service URL from @ConfigurationProperties.
    private val baseUrl = "http://localhost:8080"

    @Bean
    fun webClient(): WebClient = WebClient.builder()
        .baseUrl(baseUrl)
        .build()

    @Bean
    fun restClient(): RestClient = RestClient.builder()
        .baseUrl(baseUrl)
        .build()
}
