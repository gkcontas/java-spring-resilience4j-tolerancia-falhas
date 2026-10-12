package com.gkcontas.resilience.experiment;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.gkcontas.resilience.carrier.CarrierClient;
import com.gkcontas.resilience.carrier.CarrierQuote;
import com.gkcontas.resilience.carrier.CarrierUnavailableException;
import com.gkcontas.resilience.faults.FaultInjector;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

/**
 * An open circuit exists to fail fast. A retry with the default configuration -
 * which retries any exception - turns that into the slowest failure in the
 * system: it sleeps the whole backoff to be refused by a breaker that has not
 * even tried the network.
 *
 * <p>The fix is one line: list what is worth retrying instead of listing what
 * is not.
 */
@Component
public class OpenCircuitRetryExperiment implements Experiment {

    private static final int SAMPLES = 5;

    private final CarrierClient client;
    private final FaultInjector faults;

    public OpenCircuitRetryExperiment(CarrierClient client, FaultInjector faults) {
        this.client = client;
        this.faults = faults;
    }

    @Override
    public String name() {
        return "open-circuit-retry";
    }

    @Override
    public Object run(Map<String, String> params) {
        faults.serverError();
        return new Result(SAMPLES,
                measure(RetryConfig.custom()
                        .maxAttempts(3)
                        .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofMillis(500), 2))
                        .build(), "retry anything (the default)"),
                measure(RetryConfig.custom()
                        .maxAttempts(3)
                        .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofMillis(500), 2))
                        .retryExceptions(CarrierUnavailableException.class)
                        .build(), "retryExceptions whitelist"));
    }

    private Arm measure(RetryConfig config, String description) {
        CircuitBreaker breaker = CircuitBreaker.of("open-" + description.hashCode(),
                CircuitBreakerConfig.custom().waitDurationInOpenState(Duration.ofMinutes(5)).build());
        breaker.transitionToOpenState();

        Retry retry = Retry.of("open-" + description.hashCode(), config);
        Supplier<CarrierQuote> decorated = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(breaker, () -> client.quote("SKU-OPEN")));

        List<Long> samples = new ArrayList<>();
        for (int i = 0; i < SAMPLES; i++) {
            long start = System.nanoTime();
            try {
                decorated.get();
            } catch (RuntimeException e) {
                // the refusal is what is being timed
            }
            samples.add(System.nanoTime() - start);
        }
        Stats stats = Stats.ofNanos(samples);
        return new Arm(description, stats.p50Millis(), stats.maxMillis(),
                faults.requestCount("GET", "/carrier/quotes/([^/]+)"));
    }

    public record Arm(String retryConfiguration, double p50Millis, double maxMillis, int requestsReachingTheCarrier) {
    }

    public record Result(int samples, Arm retryAnything, Arm retryWhitelist) {
    }
}
