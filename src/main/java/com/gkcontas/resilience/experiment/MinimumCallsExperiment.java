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

/**
 * The quietest failure mode of a circuit breaker: a window of a hundred calls
 * with a minimum of a hundred. Everything fails, the configuration looks
 * reasonable in review, and the breaker never opens because a low traffic
 * endpoint does not reach the minimum before the window slides.
 *
 * <p>A time based window with a small minimum opens on the same traffic. The
 * trade-off is the other direction: it can open on three failures at three in
 * the morning.
 */
@Component
public class MinimumCallsExperiment implements Experiment {

    private static final int CALLS = 30;

    private final CarrierClient client;
    private final FaultInjector faults;

    public MinimumCallsExperiment(CarrierClient client, FaultInjector faults) {
        this.client = client;
        this.faults = faults;
    }

    @Override
    public String name() {
        return "minimum-calls";
    }

    @Override
    public Object run(Map<String, String> params) {
        faults.serverError();

        CircuitBreakerConfig countBased = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(100)
                .minimumNumberOfCalls(100)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .recordExceptions(CarrierUnavailableException.class)
                .build();

        CircuitBreakerConfig timeBased = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .recordExceptions(CarrierUnavailableException.class)
                .build();

        return new Result(CALLS,
                drive(CircuitBreaker.of("count-100-100", countBased), "COUNT_BASED 100 / min 100"),
                drive(CircuitBreaker.of("time-10s-5", timeBased), "TIME_BASED 10s / min 5"));
    }

    private Arm drive(CircuitBreaker breaker, String description) {
        Supplier<CarrierQuote> decorated = CircuitBreaker.decorateSupplier(breaker, () -> client.quote("SKU-MIN"));
        int openedAfter = -1;
        for (int i = 1; i <= CALLS; i++) {
            try {
                decorated.get();
            } catch (RuntimeException e) {
                // every call fails
            }
            if (openedAfter < 0 && breaker.getState() == CircuitBreaker.State.OPEN) {
                openedAfter = i;
            }
        }
        return new Arm(description, breaker.getState().name(), breaker.getMetrics().getNumberOfFailedCalls(),
                openedAfter);
    }

    public record Arm(String window, String breakerState, int failuresRecorded, int openedAfterCall) {
    }

    public record Result(int failingCalls, Arm countBased, Arm timeBased) {
    }
}
