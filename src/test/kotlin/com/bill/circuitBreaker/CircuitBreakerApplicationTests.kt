package com.bill.circuitBreaker

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.web.context.WebApplicationContext

@SpringBootTest
class CircuitBreakerApplicationTests {

    @Autowired
    lateinit var wac: WebApplicationContext

    @Test
    fun contextLoads() {
    }

    @Test
    fun `root redirects to actuator`() {
        assertThat(MockMvcTester.from(wac).get().uri("/"))
            .hasStatus(HttpStatus.PERMANENT_REDIRECT)
            .headers().hasValue("Location", "/actuator")
    }

}
