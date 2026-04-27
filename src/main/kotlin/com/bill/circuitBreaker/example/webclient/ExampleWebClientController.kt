package com.bill.circuitBreaker.example.webclient

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/example")
class ExampleWebClientController(
    private val kotlinWebClientService: KotlinWebClientService,
    @param:Qualifier("javaRestClientService")
    private val javaRestClientService: JavaRestClientService
) {

    // WebClient — annotation style
    @GetMapping("/webclient/success")
    fun webClientSuccess(): Mono<String> = kotlinWebClientService.callSuccess()

    @GetMapping("/webclient/failure")
    fun webClientFailure(): Mono<String> = kotlinWebClientService.callFailure()

    // WebClient — functional (Reactor transform) style
    @GetMapping("/webclient/functional")
    fun webClientFunctional(): Mono<String> = kotlinWebClientService.callSuccessFunctional()

    // RestClient (Java) — annotation style
    @GetMapping("/restclient/success")
    fun restClientSuccess(): JavaRestClientService.DownstreamResponse =
        javaRestClientService.callSuccess()

    @GetMapping("/restclient/failure")
    fun restClientFailure(): JavaRestClientService.DownstreamResponse =
        javaRestClientService.callFailure()

    // RestClient (Java) — functional (Decorators) style
    @GetMapping("/restclient/functional")
    fun restClientFunctional(): JavaRestClientService.DownstreamResponse =
        javaRestClientService.callSuccessFunctional()
}
