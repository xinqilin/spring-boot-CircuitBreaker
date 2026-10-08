package com.bill.circuitBreaker.exception

import org.springframework.web.client.HttpClientErrorException
import java.util.function.Predicate

/**
 * @author Bill.Lin 2024/6/29
 */
class RecordFailurePredicate : Predicate<Throwable> {

    override fun test(throwable: Throwable): Boolean {
        // Mirrors the 'basic' recordExceptions list: business errors and 4xx are the caller's fault, not the downstream's
        return throwable !is BusinessException && throwable !is HttpClientErrorException
    }
}