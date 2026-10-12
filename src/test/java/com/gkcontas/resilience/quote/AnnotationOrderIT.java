package com.gkcontas.resilience.quote;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.gkcontas.resilience.support.IntegrationTestBase;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * The order the ANNOTATIONS compose in is not an opinion, it is a default of
 * the library, and it is the one thing a reviewer cannot see by reading the
 * method. Asserted here instead of claimed in a comment.
 */
class AnnotationOrderIT extends IntegrationTestBase {

    @Autowired
    QuoteService service;

    @Test
    @DisplayName("@Retry is the outermost aspect: one logical call sends three requests")
    void retryIsOutermost() {
        faults.serverError();

        QuoteResponse response = service.quote("SKU-ORDER");

        assertThat(response.source()).isEqualTo(QuoteSource.FALLBACK);
        assertThat(faults.requestCount("GET", "/carrier/quotes/([^/]+)")).isEqualTo(3);
    }

    @Test
    @DisplayName("and the circuit breaker, being inside it, records all three attempts")
    void theBreakerRecordsEveryAttempt() {
        faults.serverError();
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker("carrier");

        service.quote("SKU-ORDER");

        assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isEqualTo(3);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(3);
    }

    @Test
    @DisplayName("a carrier that fails twice and recovers is answered live, because the retry is above")
    void retrySucceedsOnTheThirdAttempt() {
        faults.failThenRecover(2);

        QuoteResponse response = service.quote("SKU-ORDER");

        assertThat(response.source()).isEqualTo(QuoteSource.CARRIER);
        assertThat(faults.requestCount("GET", "/carrier/quotes/([^/]+)")).isEqualTo(3);
    }

    @Test
    @DisplayName("the same carrier with the fallback on the inner annotation answers degraded after one attempt")
    void innerFallbackDisablesTheRetry() {
        faults.failThenRecover(2);

        QuoteResponse response = service.quoteWithInnerFallback("SKU-ORDER");

        assertThat(response.source()).isEqualTo(QuoteSource.FALLBACK);
        assertThat(faults.requestCount("GET", "/carrier/quotes/([^/]+)")).isEqualTo(1);
    }

    @Test
    @DisplayName("a fallback typed to Throwable answers 200 to a NullPointerException in our own code")
    void broadFallbackSwallowsTheBug() {
        QuoteResponse response = service.quoteWithBroadFallback("SKU-BUG", true);

        assertThat(response.source()).isEqualTo(QuoteSource.FALLBACK);
        assertThat(faults.requestCount("GET", "/carrier/quotes/([^/]+)")).isZero();
    }

    @Test
    @DisplayName("a fallback typed to the failure it replaces lets the same bug propagate")
    void typedFallbackLetsTheBugThrough() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> service.quoteWithNarrowFallback("SKU-BUG", true))
                .isInstanceOf(NullPointerException.class);
    }
}
