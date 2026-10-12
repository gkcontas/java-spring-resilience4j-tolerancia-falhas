package com.gkcontas.resilience.experiment;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.gkcontas.resilience.carrier.CarrierClient;
import com.gkcontas.resilience.carrier.CarrierQuote;
import com.gkcontas.resilience.carrier.CarrierUnavailableException;
import com.gkcontas.resilience.faults.FaultInjector;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

/**
 * The central question of the subject: does the circuit breaker sit inside or
 * outside the retry?
 *
 * <p>Outside the retry (the Resilience4j default), the breaker records every
 * ATTEMPT - the window fills three times faster and the circuit opens while the
 * same handful of users is still waiting. Inside, it records one result per
 * LOGICAL call: the window is honest about how many users were served, and the
 * breaker may never reach its minimum on a low traffic endpoint while the
 * dependency is being hammered with three times the requests.
 */
@Component
public class DecoratorOrderExperiment implements Experiment {

    private static final int LOGICAL_CALLS = 5;
    private static final int MAX_ATTEMPTS = 3;

    private final CarrierClient client;
    private final FaultInjector faults;

    public DecoratorOrderExperiment(CarrierClient client, FaultInjector faults) {
        this.client = client;
        this.faults = faults;
    }

    @Override
    public String name() {
        return "decorator-order";
    }

    @Override
    public Object run(Map<String, String> params) {
        return new Result(LOGICAL_CALLS, MAX_ATTEMPTS, measure(true), measure(false));
    }

    private Composition measure(boolean retryOutside) {
        faults.serverError();
        CircuitBreaker breaker = CircuitBreaker.of("order-" + retryOutside, breakerConfig());
        Retry retry = Retry.of("order-" + retryOutside, retryConfig());

        Supplier<CarrierQuote> call = () -> client.quote("SKU-ORDER");
        Supplier<CarrierQuote> decorated = retryOutside
                ? Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(breaker, call))
                : CircuitBreaker.decorateSupplier(breaker, Retry.decorateSupplier(retry, call));

        long start = System.nanoTime();
        int refused = 0;
        for (int i = 0; i < LOGICAL_CALLS; i++) {
            try {
                decorated.get();
            } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
                refused++;
            } catch (RuntimeException e) {
                // expected: the carrier is answering 500 to everything
            }
        }
        long elapsed = System.nanoTime() - start;

        return new Composition(
                retryOutside ? "Retry(CircuitBreaker(call))" : "CircuitBreaker(Retry(call))",
                breaker.getMetrics().getNumberOfBufferedCalls(),
                breaker.getMetrics().getNumberOfFailedCalls(),
                breaker.getState().name(),
                refused,
                faults.requestCount("GET", "/carrier/quotes/([^/]+)"),
                Math.round(elapsed / 1_000_000.0));
    }

    /**
     * Twenty slots, ten needed before the rate is computed. With the retry
     * outside, five logical calls are enough to reach the minimum; inside, they
     * are not.
     */
    private static CircuitBreakerConfig breakerConfig() {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
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
                .waitDuration(Duration.ofMillis(20))
                .retryExceptions(CarrierUnavailableException.class)
                .build();
    }

    public record Composition(String composition, int callsRecordedByBreaker, int failuresRecorded,
            String breakerState, int callsRefusedByOpenCircuit, int requestsReachingTheCarrier,
            long elapsedMillis) {
    }

    public record Result(int logicalCalls, int maxAttempts, Composition retryOutside, Composition retryInside) {
    }
}
