package com.gkcontas.resilience.experiment;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.gkcontas.resilience.carrier.CarrierClient;
import com.gkcontas.resilience.carrier.CarrierQuote;
import com.gkcontas.resilience.carrier.CarrierUnavailableException;
import com.gkcontas.resilience.carrier.QuoteNotFoundException;
import com.gkcontas.resilience.faults.FaultInjector;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

/**
 * The carrier is healthy and answering 404 for SKUs it does not quote. With the
 * default configuration - "any exception is a failure" - twenty of those open
 * the circuit, and the SKUs that DO have a quote start being refused by the
 * application itself.
 */
@Component
public class BusinessErrorExperiment implements Experiment {

    private static final int CALLS = 20;

    private final CarrierClient client;
    private final FaultInjector faults;

    public BusinessErrorExperiment(CarrierClient client, FaultInjector faults) {
        this.client = client;
        this.faults = faults;
    }

    @Override
    public String name() {
        return "business-error";
    }

    @Override
    public Object run(Map<String, String> params) {
        faults.notFound();
        return new Result(CALLS,
                drive(CircuitBreaker.of("anything-is-failure", config(false))),
                drive(CircuitBreaker.of("classified", config(true))));
    }

    private Arm drive(CircuitBreaker breaker) {
        Supplier<CarrierQuote> decorated = CircuitBreaker.decorateSupplier(breaker, () -> client.quote("SKU-GHOST"));
        int notFound = 0;
        int refused = 0;
        for (int i = 0; i < CALLS; i++) {
            try {
                decorated.get();
            } catch (QuoteNotFoundException e) {
                notFound++;
            } catch (CallNotPermittedException e) {
                refused++;
            }
        }
        return new Arm(breaker.getState().name(), breaker.getMetrics().getNumberOfFailedCalls(), notFound, refused);
    }

    private static CircuitBreakerConfig config(boolean classified) {
        CircuitBreakerConfig.Builder builder = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30));
        if (classified) {
            builder.recordExceptions(CarrierUnavailableException.class);
        }
        return builder.build();
    }

    public record Arm(String breakerState, int failuresRecorded, int notFoundAnswers, int callsRefused) {
    }

    public record Result(int calls, Arm anyExceptionIsFailure, Arm onlyTechnicalIsFailure) {
    }
}
