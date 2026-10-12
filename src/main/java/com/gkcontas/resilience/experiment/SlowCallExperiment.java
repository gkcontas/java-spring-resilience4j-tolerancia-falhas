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

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

/**
 * A dependency that answers 200 after eight hundred milliseconds never fails.
 * A breaker watching only the failure rate stays closed forever while every
 * caller's thread is parked - the outage that does not look like one.
 *
 * <p>The same run also prices the two outcomes: what a call costs when it goes
 * to the slow service, and what it costs when the open circuit refuses it.
 */
@Component
public class SlowCallExperiment implements Experiment {

    private static final int CALLS = 20;
    private static final int REFUSED_SAMPLES = 200;
    private static final Duration DELAY = Duration.ofMillis(800);

    private final CarrierClient client;
    private final FaultInjector faults;

    public SlowCallExperiment(CarrierClient client, FaultInjector faults) {
        this.client = client;
        this.faults = faults;
    }

    @Override
    public String name() {
        return "slow-call";
    }

    @Override
    public Object run(Map<String, String> params) {
        faults.slow(DELAY);

        CircuitBreaker failureOnly = CircuitBreaker.of("slow-failure-only", config(false));
        Arm withoutDetection = drive(failureOnly);

        CircuitBreaker slowAware = CircuitBreaker.of("slow-aware", config(true));
        Arm withDetection = drive(slowAware);

        Stats refused = measureRefused(slowAware);

        return new Result(CALLS, DELAY.toMillis(), withoutDetection, withDetection,
                round(refused.p50Millis() * 1000), round(refused.p95Millis() * 1000));
    }

    private Arm drive(CircuitBreaker breaker) {
        Supplier<CarrierQuote> decorated = CircuitBreaker.decorateSupplier(breaker, () -> client.quote("SKU-SLOW"));
        List<Long> admitted = new ArrayList<>();
        int refused = 0;
        for (int i = 0; i < CALLS; i++) {
            long start = System.nanoTime();
            try {
                decorated.get();
                admitted.add(System.nanoTime() - start);
            } catch (CallNotPermittedException e) {
                refused++;
            } catch (RuntimeException e) {
                admitted.add(System.nanoTime() - start);
            }
        }
        return new Arm(breaker.getState().name(),
                breaker.getMetrics().getNumberOfFailedCalls(),
                breaker.getMetrics().getNumberOfSlowCalls(),
                refused,
                Stats.ofNanos(admitted).p50Millis());
    }

    private Stats measureRefused(CircuitBreaker breaker) {
        if (breaker.getState() != CircuitBreaker.State.OPEN) {
            breaker.transitionToOpenState();
        }
        Supplier<CarrierQuote> decorated = CircuitBreaker.decorateSupplier(breaker, () -> client.quote("SKU-SLOW"));
        List<Long> samples = new ArrayList<>();
        for (int i = 0; i < REFUSED_SAMPLES; i++) {
            long start = System.nanoTime();
            try {
                decorated.get();
            } catch (RuntimeException e) {
                // the refusal is the measurement
            }
            samples.add(System.nanoTime() - start);
        }
        return Stats.ofNanos(samples);
    }

    /**
     * Same window in both arms. The only difference is whether a call that
     * takes longer than the threshold counts as a slow call.
     */
    private static CircuitBreakerConfig config(boolean detectSlowCalls) {
        CircuitBreakerConfig.Builder builder = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .recordExceptions(CarrierUnavailableException.class);
        if (detectSlowCalls) {
            builder.slowCallDurationThreshold(Duration.ofMillis(500)).slowCallRateThreshold(50);
        } else {
            // The default is 60s / 100%, which in practice means "never".
            builder.slowCallDurationThreshold(Duration.ofSeconds(60)).slowCallRateThreshold(100);
        }
        return builder.build();
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public record Arm(String breakerState, int failuresRecorded, int slowCallsRecorded, int callsRefused,
            double admittedP50Millis) {
    }

    public record Result(int calls, long carrierDelayMillis, Arm failureRateOnly, Arm withSlowCallDetection,
            double refusedP50Micros, double refusedP95Micros) {
    }
}
