package com.gkcontas.resilience.experiment;

import java.time.Duration;
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
 * How long an open circuit keeps refusing a service that is already back, and
 * what the number of probes buys.
 *
 * <p>With one permitted call in half-open, a single request decides for
 * everybody: against a service that is still unstable the breaker flips back to
 * open on the first bad probe. With three, the decision is a rate - the cost is
 * two more requests sent to a service that may still be sick.
 */
@Component
public class HalfOpenRecoveryExperiment implements Experiment {

    private static final int CYCLES = 10;

    private final CarrierClient client;
    private final FaultInjector faults;

    public HalfOpenRecoveryExperiment(CarrierClient client, FaultInjector faults) {
        this.client = client;
        this.faults = faults;
    }

    @Override
    public String name() {
        return "half-open-recovery";
    }

    @Override
    public Object run(Map<String, String> params) {
        return new Result(measureRecovery(), flapping(1), flapping(3));
    }

    /** Time between the carrier coming back and the circuit letting traffic in. */
    private Recovery measureRecovery() {
        faults.serverError();
        CircuitBreaker breaker = CircuitBreaker.of("recovery", config(3));
        Supplier<CarrierQuote> decorated = CircuitBreaker.decorateSupplier(breaker,
                () -> client.quote("SKU-RECOVER"));

        for (int i = 0; i < 10; i++) {
            swallow(decorated);
        }
        String stateAfterOutage = breaker.getState().name();

        faults.healthy();
        faults.resetJournal();
        long start = System.nanoTime();
        int refusedWhileOpen = 0;
        while (breaker.getState() != CircuitBreaker.State.CLOSED
                && System.nanoTime() - start < Duration.ofSeconds(20).toNanos()) {
            try {
                decorated.get();
            } catch (CallNotPermittedException e) {
                refusedWhileOpen++;
            } catch (RuntimeException e) {
                // still failing
            }
            sleep(20);
        }
        long recovered = Math.round((System.nanoTime() - start) / 1_000_000.0);
        return new Recovery(stateAfterOutage, breaker.getState().name(), recovered, refusedWhileOpen,
                faults.requestCount("GET", "/carrier/quotes/([^/]+)"));
    }

    /** The carrier fails one call in three; the probes decide. */
    private Flapping flapping(int permittedCalls) {
        faults.failsOneIn(3);
        CircuitBreaker breaker = CircuitBreaker.of("flap-" + permittedCalls, config(permittedCalls));
        Supplier<CarrierQuote> decorated = CircuitBreaker.decorateSupplier(breaker,
                () -> client.quote("SKU-FLAP"));

        int closed = 0;
        int reopened = 0;
        for (int cycle = 0; cycle < CYCLES; cycle++) {
            breaker.transitionToOpenState();
            breaker.transitionToHalfOpenState();
            for (int probe = 0; probe < permittedCalls; probe++) {
                swallow(decorated);
            }
            if (breaker.getState() == CircuitBreaker.State.CLOSED) {
                closed++;
            } else {
                reopened++;
            }
        }
        return new Flapping(permittedCalls, CYCLES, closed, reopened, CYCLES * permittedCalls);
    }

    private static CircuitBreakerConfig config(int permittedCalls) {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(1))
                .permittedNumberOfCallsInHalfOpenState(permittedCalls)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordExceptions(CarrierUnavailableException.class)
                .build();
    }

    private static void swallow(Supplier<CarrierQuote> supplier) {
        try {
            supplier.get();
        } catch (RuntimeException e) {
            // the state, not the answer, is what this experiment reads
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public record Recovery(String stateAfterOutage, String stateAfterRecovery, long recoveryMillis,
            int callsRefusedWhileCarrierWasHealthy, int probesSentToTheCarrier) {
    }

    public record Flapping(int permittedCallsInHalfOpen, int cycles, int closedOnTheProbes, int reopened,
            int probesSentToASickService) {
    }

    public record Result(Recovery recovery, Flapping onePermittedCall, Flapping threePermittedCalls) {
    }
}
