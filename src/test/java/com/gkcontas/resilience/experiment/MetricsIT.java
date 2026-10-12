package com.gkcontas.resilience.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;

import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;

import com.gkcontas.resilience.quote.QuoteResponse;
import com.gkcontas.resilience.support.IntegrationTestBase;

/**
 * A policy nobody can see is a policy nobody will tune. Each decorator
 * publishes its own series, and the outcome is a tag - otherwise a healthy
 * service and an open circuit look the same on a dashboard of 200s.
 *
 * <p>{@code @AutoConfigureObservability} is not decoration: Spring Boot turns
 * metrics export OFF under test, so without it the scrape endpoint answers 404
 * and a test like this passes by asserting on nothing.
 */
@AutoConfigureObservability
class MetricsIT extends IntegrationTestBase {

    @Autowired
    TestRestTemplate rest;

    @Test
    @DisplayName("the circuit breaker publishes its state and its calls by outcome")
    void circuitBreakerMeters() {
        faults.serverError();
        for (int i = 0; i < 10; i++) {
            rest.getForEntity("/quotes/SKU-1", QuoteResponse.class);
        }

        String metrics = scrape();

        assertThat(metrics).contains("resilience4j_circuitbreaker_state");
        assertThat(metrics).contains("resilience4j_circuitbreaker_calls_seconds_count");
        assertThat(metrics).contains("kind=\"failed\"");
        assertThat(metrics).contains("name=\"carrier\"");
    }

    @Test
    @DisplayName("the retry publishes the four outcomes it can have")
    void retryMeters() {
        faults.failThenRecover(1);
        rest.getForEntity("/quotes/SKU-1", QuoteResponse.class);

        String metrics = scrape();

        assertThat(metrics).contains("resilience4j_retry_calls_total");
        assertThat(metrics).contains("kind=\"successful_with_retry\"");
    }

    @Test
    @DisplayName("the bulkhead publishes how much of it is left")
    void bulkheadMeters() {
        rest.getForEntity("/quotes/SKU-1", QuoteResponse.class);

        String metrics = scrape();

        assertThat(metrics).contains("resilience4j_bulkhead_available_concurrent_calls");
        assertThat(metrics).contains("resilience4j_bulkhead_max_allowed_concurrent_calls");
    }

    @Test
    @DisplayName("the actuator exposes the circuit breaker state as a health detail")
    void healthDetail() {
        String health = rest.getForObject("/actuator/health", String.class);

        assertThat(health).contains("circuitBreakers");
        assertThat(health).contains("carrier");
    }

    private String scrape() {
        return rest.getForObject("/actuator/prometheus", String.class);
    }
}
