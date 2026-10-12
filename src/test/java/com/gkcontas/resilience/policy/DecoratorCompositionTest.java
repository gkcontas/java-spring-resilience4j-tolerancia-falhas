package com.gkcontas.resilience.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gkcontas.resilience.carrier.CarrierUnavailableException;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

/**
 * The composition order is the whole subject, and it is decided by one line of
 * wiring. These assertions are the contract the experiments then measure
 * against a real socket.
 */
class DecoratorCompositionTest {

    private static final int MAX_ATTEMPTS = 3;

    private static CircuitBreakerConfig breakerConfig() {
        return CircuitBreakerConfig.custom()
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .recordExceptions(CarrierUnavailableException.class)
                .build();
    }

    private static RetryConfig retryConfig() {
        return RetryConfig.custom()
                .maxAttempts(MAX_ATTEMPTS)
                .waitDuration(Duration.ofMillis(1))
                .retryExceptions(CarrierUnavailableException.class)
                .build();
    }

    @Test
    @DisplayName("retry outside: the breaker records every attempt, so one logical call fills three slots")
    void retryOutsideRecordsEveryAttempt() {
        CircuitBreaker breaker = CircuitBreaker.of("outside", breakerConfig());
        Retry retry = Retry.of("outside", retryConfig());
        AtomicInteger calls = new AtomicInteger();

        Supplier<String> decorated = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(breaker, failing(calls)));
        swallow(decorated);

        assertThat(calls.get()).isEqualTo(MAX_ATTEMPTS);
        assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isEqualTo(MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("retry inside: the breaker records one result per logical call")
    void retryInsideRecordsOneResult() {
        CircuitBreaker breaker = CircuitBreaker.of("inside", breakerConfig());
        Retry retry = Retry.of("inside", retryConfig());
        AtomicInteger calls = new AtomicInteger();

        Supplier<String> decorated = CircuitBreaker.decorateSupplier(breaker,
                Retry.decorateSupplier(retry, failing(calls)));
        swallow(decorated);

        assertThat(calls.get()).isEqualTo(MAX_ATTEMPTS);
        assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("retry outside reaches the minimum in four logical calls; inside it does not reach it in five")
    void theOrderDecidesWhenTheCircuitOpens() {
        CircuitBreaker outsideBreaker = CircuitBreaker.of("outside-open", breakerConfig());
        Supplier<String> outside = Retry.decorateSupplier(Retry.of("outside-open", retryConfig()),
                CircuitBreaker.decorateSupplier(outsideBreaker, failing(new AtomicInteger())));

        CircuitBreaker insideBreaker = CircuitBreaker.of("inside-open", breakerConfig());
        Supplier<String> inside = CircuitBreaker.decorateSupplier(insideBreaker,
                Retry.decorateSupplier(Retry.of("inside-open", retryConfig()), failing(new AtomicInteger())));

        for (int i = 0; i < 5; i++) {
            swallow(outside);
            swallow(inside);
        }

        assertThat(outsideBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(insideBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(insideBreaker.getMetrics().getNumberOfBufferedCalls()).isEqualTo(5);
    }

    @Test
    @DisplayName("with the retry outside, an open circuit stops the remaining attempts from reaching the service")
    void openCircuitShortCircuitsTheRemainingAttempts() {
        CircuitBreaker breaker = CircuitBreaker.of("short", breakerConfig());
        breaker.transitionToOpenState();
        AtomicInteger calls = new AtomicInteger();

        Supplier<String> decorated = Retry.decorateSupplier(Retry.of("short", retryConfig()),
                CircuitBreaker.decorateSupplier(breaker, failing(calls)));
        swallow(decorated);

        assertThat(calls.get()).isZero();
    }

    @Test
    @DisplayName("with the retry inside, the open circuit still costs the full retry budget")
    void retryInsideKeepsRetryingTheService() {
        CircuitBreaker breaker = CircuitBreaker.of("inside-short", breakerConfig());
        breaker.transitionToOpenState();
        AtomicInteger calls = new AtomicInteger();

        Supplier<String> decorated = CircuitBreaker.decorateSupplier(breaker,
                Retry.decorateSupplier(Retry.of("inside-short", retryConfig()), failing(calls)));
        swallow(decorated);

        // The breaker is the outer one here: it refuses before the retry runs.
        assertThat(calls.get()).isZero();
    }

    private static Supplier<String> failing(AtomicInteger counter) {
        return () -> {
            counter.incrementAndGet();
            throw new CarrierUnavailableException("down");
        };
    }

    private static void swallow(Supplier<String> supplier) {
        try {
            supplier.get();
        } catch (RuntimeException e) {
            // the counters are the assertion
        }
    }
}
