package com.bill.circuitBreaker.exception

import org.springframework.web.client.HttpServerErrorException
import java.io.IOException
import java.util.concurrent.TimeoutException
import java.util.function.Predicate

/**
 * @author Bill.Lin 2024/6/29
 */
class RecordFailurePredicate : Predicate<Throwable> {

    // Allow-list mirroring the 'basic' recordExceptions: only downstream failures count.
    // A deny-list (e.g. "everything except BusinessException") would also record Resilience4j's own
    // rejections such as RequestNotPermitted, letting rate limiting trip the circuit breaker.
    override fun test(throwable: Throwable): Boolean {
        return throwable is HttpServerErrorException || throwable is TimeoutException || throwable is IOException
    }
}
