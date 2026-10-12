package com.gkcontas.resilience.quote;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.gkcontas.resilience.api.ErrorResponse;
import com.gkcontas.resilience.support.IntegrationTestBase;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

class QuoteApiIT extends IntegrationTestBase {

    @Autowired
    TestRestTemplate rest;

    @Test
    @DisplayName("a healthy carrier is answered as a live quote")
    void liveQuote() {
        ResponseEntity<QuoteResponse> response = rest.getForEntity("/quotes/SKU-1", QuoteResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().source()).isEqualTo(QuoteSource.CARRIER);
    }

    @Test
    @DisplayName("a broken carrier is answered by the fallback, with the source saying so")
    void fallbackWhenCarrierIsDown() {
        faults.serverError();

        ResponseEntity<QuoteResponse> response = rest.getForEntity("/quotes/SKU-1", QuoteResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().source()).isEqualTo(QuoteSource.FALLBACK);
    }

    @Test
    @DisplayName("a business 404 is a 404 to the client, and no fallback price is invented")
    void notFoundIsNotFallback() {
        faults.notFound();

        ResponseEntity<ErrorResponse> response = rest.getForEntity("/quotes/SKU-GHOST", ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().error()).isEqualTo("quote_not_found");
    }

    @Test
    @DisplayName("twenty business 404s leave the circuit closed")
    void businessErrorsDoNotOpenTheCircuit() {
        faults.notFound();

        for (int i = 0; i < 20; i++) {
            rest.getForEntity("/quotes/SKU-GHOST", ErrorResponse.class);
        }

        assertThat(circuitBreakerRegistry.circuitBreaker("carrier").getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("a broken carrier opens the circuit, and the carrier stops being called at all")
    void circuitOpensAndStopsTheTraffic() {
        faults.serverError();

        for (int i = 0; i < 10; i++) {
            rest.getForEntity("/quotes/SKU-1", QuoteResponse.class);
        }
        assertThat(circuitBreakerRegistry.circuitBreaker("carrier").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);

        faults.resetJournal();
        ResponseEntity<QuoteResponse> response = rest.getForEntity("/quotes/SKU-1", QuoteResponse.class);

        assertThat(response.getBody().source()).isEqualTo(QuoteSource.FALLBACK);
        assertThat(faults.requestCount("GET", "/carrier/quotes/([^/]+)")).isZero();
    }

    @Test
    @DisplayName("the unprotected endpoint has no fallback: the failure reaches the client as a 503")
    void unprotectedPropagates() {
        faults.serverError();

        ResponseEntity<ErrorResponse> response = rest.getForEntity("/quotes/unprotected/SKU-1", ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(faults.requestCount("GET", "/carrier/quotes/([^/]+)")).isEqualTo(1);
    }

    @Test
    @DisplayName("the local endpoint answers without touching the carrier")
    void catalogIsLocal() {
        faults.serverError();

        ResponseEntity<String> response = rest.getForEntity("/catalog/SKU-1", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(faults.requestCount("GET", "/carrier/quotes/([^/]+)")).isZero();
    }

    @Test
    @DisplayName("an invalid shipment request is rejected with the declared English message")
    void invalidShipment() {
        ResponseEntity<ErrorResponse> response = rest.postForEntity("/shipments",
                java.util.Map.of("sku", "", "quantity", 1), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("sku must not be blank");
    }
}
