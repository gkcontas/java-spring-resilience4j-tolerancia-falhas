package com.gkcontas.resilience.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gkcontas.resilience.carrier.CarrierRejectedException;
import com.gkcontas.resilience.carrier.CarrierUnavailableException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

class RetryBehaviourTest {

    @Test
    @DisplayName("maxAttempts counts the first call, so three means two retries")
    void maxAttemptsIncludesTheFirstCall() {
        AtomicInteger calls = new AtomicInteger();
        Retry retry = Retry.of("attempts", RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(1))
                .retryExceptions(CarrierUnavailableException.class)
                .build());

        swallow(Retry.decorateSupplier(retry, failing(calls)));

        assertThat(calls.get()).isEqualTo(3);
        assertThat(retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt()).isEqualTo(1);
    }

    @Test
    @DisplayName("an exception outside retryExceptions is not retried at all")
    void nonRetryableFailsOnTheFirstAttempt() {
        AtomicInteger calls = new AtomicInteger();
        Retry retry = Retry.of("whitelist", RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(1))
                .retryExceptions(CarrierUnavailableException.class)
                .build());

        assertThatThrownBy(() -> Retry.decorateSupplier(retry, (Supplier<String>) () -> {
            calls.incrementAndGet();
            throw new CarrierRejectedException(400, "bad payload");
        }).get()).isInstanceOf(CarrierRejectedException.class);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("the default configuration retries CallNotPermittedException, which an open circuit throws")
    void defaultRetriesTheOpenCircuit() {
        CircuitBreaker breaker = CircuitBreaker.of("retried-open", io.github.resilience4j.circuitbreaker
                .CircuitBreakerConfig.custom().waitDurationInOpenState(Duration.ofMinutes(5)).build());
        breaker.transitionToOpenState();
        Retry retry = Retry.of("default-retry", RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(1))
                .build());

        swallow(Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(breaker, () -> "never runs")));

        assertThat(retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt()).isEqualTo(1);
        assertThat(breaker.getMetrics().getNumberOfNotPermittedCalls()).isEqualTo(3);
    }

    @Test
    @DisplayName("a whitelist of retryable exceptions leaves the open circuit to fail fast")
    void whitelistDoesNotRetryTheOpenCircuit() {
        CircuitBreaker breaker = CircuitBreaker.of("fast-open", io.github.resilience4j.circuitbreaker
                .CircuitBreakerConfig.custom().waitDurationInOpenState(Duration.ofMinutes(5)).build());
        breaker.transitionToOpenState();
        Retry retry = Retry.of("whitelisted-retry", RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(1))
                .retryExceptions(CarrierUnavailableException.class)
                .build());

        assertThatThrownBy(() -> Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(breaker, () -> "never runs")).get())
                .isInstanceOf(CallNotPermittedException.class);

        assertThat(breaker.getMetrics().getNumberOfNotPermittedCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("exponential backoff doubles: 200, 400")
    void exponentialBackoff() {
        IntervalFunction interval = IntervalFunction.ofExponentialBackoff(Duration.ofMillis(200), 2);

        assertThat(interval.apply(1)).isEqualTo(200L);
        assertThat(interval.apply(2)).isEqualTo(400L);
        assertThat(interval.apply(3)).isEqualTo(800L);
    }

    @Test
    @DisplayName("without jitter every client waits exactly the same; with it they spread around the same mean")
    void randomizedBackoffSpreads() {
        IntervalFunction fixed = IntervalFunction.ofExponentialBackoff(Duration.ofMillis(200), 2);
        IntervalFunction jittered = IntervalFunction.ofExponentialRandomBackoff(Duration.ofMillis(200), 2, 0.5);

        java.util.Set<Long> fixedWaits = new java.util.HashSet<>();
        java.util.Set<Long> jitteredWaits = new java.util.HashSet<>();
        for (int i = 0; i < 50; i++) {
            fixedWaits.add(fixed.apply(1));
            jitteredWaits.add(jittered.apply(1));
        }

        assertThat(fixedWaits).hasSize(1);
        assertThat(jitteredWaits.size()).isGreaterThan(10);
        assertThat(jitteredWaits).allSatisfy(wait -> assertThat(wait).isBetween(100L, 300L));
    }

    @Test
    @DisplayName("a retry that eventually succeeds is counted apart from one that never does")
    void successAfterRetryIsItsOwnMetric() {
        AtomicInteger calls = new AtomicInteger();
        Retry retry = Retry.of("eventual", RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(1))
                .retryExceptions(CarrierUnavailableException.class)
                .build());

        String result = Retry.decorateSupplier(retry, (Supplier<String>) () -> {
            if (calls.incrementAndGet() < 3) {
                throw new CarrierUnavailableException("down");
            }
            return "ok";
        }).get();

        assertThat(result).isEqualTo("ok");
        assertThat(retry.getMetrics().getNumberOfSuccessfulCallsWithRetryAttempt()).isEqualTo(1);
        assertThat(retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt()).isZero();
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
            // the metrics are the assertion
        }
    }
}
