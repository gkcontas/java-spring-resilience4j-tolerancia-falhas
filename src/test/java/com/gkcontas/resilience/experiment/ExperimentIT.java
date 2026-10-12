package com.gkcontas.resilience.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.gkcontas.resilience.support.IntegrationTestBase;

/**
 * Every experiment the README quotes runs here, and the assertion is the
 * RELATION the README claims - not the exact millisecond, which depends on the
 * machine. If an experiment stops demonstrating its point, the build says so.
 */
class ExperimentIT extends IntegrationTestBase {

    @Autowired
    DecoratorOrderExperiment decoratorOrder;
    @Autowired
    BusinessErrorExperiment businessError;
    @Autowired
    MinimumCallsExperiment minimumCalls;
    @Autowired
    OpenCircuitRetryExperiment openCircuitRetry;
    @Autowired
    NonIdempotentRetryExperiment nonIdempotentRetry;
    @Autowired
    FallbackExperiment fallback;

    @Test
    @DisplayName("the retry outside the breaker records three times as many calls, and opens the circuit")
    void decoratorOrder() {
        DecoratorOrderExperiment.Result result =
                (DecoratorOrderExperiment.Result) decoratorOrder.run(Map.of());

        assertThat(result.retryOutside().callsRecordedByBreaker())
                .isGreaterThan(result.retryInside().callsRecordedByBreaker());
        assertThat(result.retryOutside().breakerState()).isEqualTo("OPEN");
        assertThat(result.retryInside().breakerState()).isEqualTo("CLOSED");
        assertThat(result.retryInside().callsRecordedByBreaker()).isEqualTo(result.logicalCalls());
    }

    @Test
    @DisplayName("business 404s open the unclassified breaker and leave the classified one closed")
    void businessError() {
        BusinessErrorExperiment.Result result = (BusinessErrorExperiment.Result) businessError.run(Map.of());

        assertThat(result.anyExceptionIsFailure().breakerState()).isEqualTo("OPEN");
        assertThat(result.anyExceptionIsFailure().callsRefused()).isPositive();
        assertThat(result.onlyTechnicalIsFailure().breakerState()).isEqualTo("CLOSED");
        assertThat(result.onlyTechnicalIsFailure().callsRefused()).isZero();
        assertThat(result.onlyTechnicalIsFailure().notFoundAnswers()).isEqualTo(result.calls());
    }

    @Test
    @DisplayName("a window of a hundred with a minimum of a hundred never opens; a time based one does")
    void minimumCalls() {
        MinimumCallsExperiment.Result result = (MinimumCallsExperiment.Result) minimumCalls.run(Map.of());

        assertThat(result.countBased().breakerState()).isEqualTo("CLOSED");
        assertThat(result.countBased().failuresRecorded()).isEqualTo(result.failingCalls());
        assertThat(result.timeBased().breakerState()).isEqualTo("OPEN");
        assertThat(result.timeBased().openedAfterCall()).isLessThanOrEqualTo(10);
    }

    @Test
    @DisplayName("retrying an open circuit costs the whole backoff; a whitelist makes it free")
    void openCircuitRetry() {
        OpenCircuitRetryExperiment.Result result =
                (OpenCircuitRetryExperiment.Result) openCircuitRetry.run(Map.of());

        assertThat(result.retryAnything().p50Millis()).isGreaterThan(1_000.0);
        assertThat(result.retryWhitelist().p50Millis()).isLessThan(5.0);
        assertThat(result.retryAnything().requestsReachingTheCarrier()).isZero();
    }

    @Test
    @DisplayName("one logical shipment with a retry becomes three shipments on the carrier side")
    void nonIdempotentRetry() {
        NonIdempotentRetryExperiment.Result result =
                (NonIdempotentRetryExperiment.Result) nonIdempotentRetry.run(Map.of());

        assertThat(result.shipmentsRecordedWithRetry()).isEqualTo(3);
        assertThat(result.shipmentsRecordedWithoutRetry()).isEqualTo(1);
        assertThat(result.withRetryOutcome()).isEqualTo("CarrierUnavailableException");
    }

    @Test
    @DisplayName("the Throwable fallback hides the planted bug; the typed one does not")
    void fallbackBehaviour() {
        FallbackExperiment.Result result = (FallbackExperiment.Result) fallback.run(Map.of());

        assertThat(result.plantedBug().throwableFallbackOutcome()).contains("FALLBACK");
        assertThat(result.plantedBug().requestsReachingTheCarrier()).isZero();
        assertThat(result.plantedBug().typedFallbackOutcome()).contains("NullPointerException");

        assertThat(result.placement().fallbackOutside().answerSource()).isEqualTo("CARRIER");
        assertThat(result.placement().fallbackOutside().attemptsReachingTheCarrier()).isEqualTo(3);
        assertThat(result.placement().fallbackInside().answerSource()).isEqualTo("FALLBACK");
        assertThat(result.placement().fallbackInside().attemptsReachingTheCarrier()).isEqualTo(1);
    }
}
