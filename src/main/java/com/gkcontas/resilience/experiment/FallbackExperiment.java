package com.gkcontas.resilience.experiment;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.gkcontas.resilience.faults.FaultInjector;
import com.gkcontas.resilience.quote.QuoteResponse;
import com.gkcontas.resilience.quote.QuoteService;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Two things about fallbacks that only show up in production.
 *
 * <p>One: a fallback typed to Throwable answers 200 to a NullPointerException
 * in your own code. The bug stops appearing in the error rate and starts
 * appearing in the fallback rate, which nobody alerts on. A fallback typed to
 * the failure it knows how to replace lets the bug propagate.
 *
 * <p>Two: a fallback on an INNER decorator answers before the outer one ever
 * sees a failure. On a carrier that fails twice and then recovers, the retry
 * above a circuit breaker that already fell back never fires - one attempt, a
 * degraded answer, and a retry policy that is pure configuration.
 */
@Component
public class FallbackExperiment implements Experiment {

    private final QuoteService service;
    private final FaultInjector faults;
    private final CircuitBreakerRegistry registry;

    public FallbackExperiment(QuoteService service, FaultInjector faults, CircuitBreakerRegistry registry) {
        this.service = service;
        this.faults = faults;
        this.registry = registry;
    }

    @Override
    public String name() {
        return "fallback";
    }

    @Override
    public Object run(Map<String, String> params) {
        return new Result(plantedBug(), placement());
    }

    private PlantedBug plantedBug() {
        faults.healthy();
        resetBreaker();

        String broadOutcome;
        try {
            QuoteResponse response = service.quoteWithBroadFallback("SKU-BUG", true);
            broadOutcome = "answered with source=" + response.source();
        } catch (RuntimeException e) {
            broadOutcome = e.getClass().getSimpleName() + " propagated";
        }
        int broadRequests = faults.requestCount("GET", "/carrier/quotes/([^/]+)");

        faults.resetJournal();
        resetBreaker();
        String narrowOutcome;
        try {
            QuoteResponse response = service.quoteWithNarrowFallback("SKU-BUG", true);
            narrowOutcome = "answered with source=" + response.source();
        } catch (RuntimeException e) {
            narrowOutcome = e.getClass().getSimpleName() + " propagated";
        }

        return new PlantedBug(broadOutcome, broadRequests, narrowOutcome);
    }

    private Placement placement() {
        faults.failThenRecover(2);
        resetBreaker();
        faults.resetJournal();
        Arm outer = call(true);

        faults.failThenRecover(2);
        resetBreaker();
        faults.resetJournal();
        Arm inner = call(false);

        return new Placement(outer, inner);
    }

    private Arm call(boolean fallbackOnOutermost) {
        String outcome;
        try {
            QuoteResponse response = fallbackOnOutermost
                    ? service.quote("SKU-PLACE")
                    : service.quoteWithInnerFallback("SKU-PLACE");
            outcome = response.source().name();
        } catch (RuntimeException e) {
            outcome = e.getClass().getSimpleName();
        }
        return new Arm(fallbackOnOutermost ? "fallback on @Retry (outermost)" : "fallback on @CircuitBreaker (inner)",
                outcome, faults.requestCount("GET", "/carrier/quotes/([^/]+)"));
    }

    private void resetBreaker() {
        registry.circuitBreaker("carrier").reset();
    }

    public record PlantedBug(String throwableFallbackOutcome, int requestsReachingTheCarrier,
            String typedFallbackOutcome) {
    }

    public record Arm(String placement, String answerSource, int attemptsReachingTheCarrier) {
    }

    public record Placement(Arm fallbackOutside, Arm fallbackInside) {
    }

    public record Result(PlantedBug plantedBug, Placement placement) {
    }
}
