package com.gkcontas.resilience.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.gkcontas.resilience.carrier.CarrierRejectedException;
import com.gkcontas.resilience.carrier.CarrierUnavailableException;
import com.gkcontas.resilience.carrier.QuoteNotFoundException;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * Each policy gets its own status. A caller has to be able to tell "the
 * dependency is down" from "we refused this on purpose".
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("a business not found is a 404, never a 503")
    void notFound() {
        ResponseEntity<ErrorResponse> response = handler.notFound(new QuoteNotFoundException("SKU-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().error()).isEqualTo("quote_not_found");
    }

    @Test
    @DisplayName("a rejected payload is a 422")
    void rejected() {
        ResponseEntity<ErrorResponse> response = handler.rejected(new CarrierRejectedException(400, "bad"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("a technical failure of the carrier is a 503")
    void unavailable() {
        ResponseEntity<ErrorResponse> response = handler.unavailable(new CarrierUnavailableException("down"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().error()).isEqualTo("carrier_unavailable");
    }

    @Test
    @DisplayName("an open circuit answers 503 with Retry-After, so the client knows when to come back")
    void circuitOpen() {
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("handler");
        breaker.transitionToOpenState();

        ResponseEntity<ErrorResponse> response = handler.circuitOpen(CallNotPermittedException
                .createCallNotPermittedException(breaker));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
        assertThat(response.getBody().error()).isEqualTo("circuit_open");
    }

    @Test
    @DisplayName("load shedding is a 429: the dependency may be perfectly healthy")
    void bulkheadFull() {
        Bulkhead bulkhead = Bulkhead.of("handler", BulkheadConfig.custom()
                .maxConcurrentCalls(1)
                .maxWaitDuration(Duration.ZERO)
                .build());

        ResponseEntity<ErrorResponse> response = handler
                .bulkheadFull(BulkheadFullException.createBulkheadFullException(bulkhead));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody().error()).isEqualTo("bulkhead_full");
    }

    @Test
    @DisplayName("a timeout is a 504 and says so in the payload")
    void timeout() {
        ResponseEntity<ErrorResponse> response = handler.timeout(new TimeoutException("late"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(response.getBody().error()).isEqualTo("carrier_timeout");
    }
}
