package com.gkcontas.resilience.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.gkcontas.resilience.support.IntegrationTestBase;

/**
 * The experiments that need wall clock time to make their point: a slow
 * carrier, a saturated container, a half-open recovery.
 */
class SlowExperimentIT extends IntegrationTestBase {

    @Autowired
    SlowCallExperiment slowCall;
    @Autowired
    TimeLimiterExperiment timeLimiter;
    @Autowired
    CancelRunningFutureExperiment cancelRunningFuture;
    @Autowired
    RetryJitterExperiment retryJitter;
    @Autowired
    HalfOpenRecoveryExperiment halfOpenRecovery;
    @Autowired
    BulkheadIsolationExperiment bulkheadIsolation;

    @Test
    @DisplayName("a carrier that only answers slowly opens the circuit only where slow calls are counted")
    void slowCall() {
        SlowCallExperiment.Result result = (SlowCallExperiment.Result) slowCall.run(Map.of());

        assertThat(result.failureRateOnly().breakerState()).isEqualTo("CLOSED");
        assertThat(result.failureRateOnly().failuresRecorded()).isZero();
        assertThat(result.withSlowCallDetection().breakerState()).isEqualTo("OPEN");
        assertThat(result.withSlowCallDetection().slowCallsRecorded()).isGreaterThanOrEqualTo(5);
        // A refused call costs microseconds; an admitted one costs the delay.
        assertThat(result.refusedP50Micros()).isLessThan(500.0);
        assertThat(result.failureRateOnly().admittedP50Millis()).isGreaterThan(500.0);
    }

    @Test
    @DisplayName("the time limiter refuses a blocking signature and bounds only the wait on an async one")
    void timeLimiter() {
        TimeLimiterExperiment.Result result = (TimeLimiterExperiment.Result) timeLimiter.run(Map.of());

        // Without a limiter the call costs the whole downstream delay.
        assertThat(result.withoutLimiter().elapsedMillis()).isGreaterThan(1_500L);
        assertThat(result.withoutLimiter().outcome()).isEqualTo("answered");

        // On a blocking signature the aspect throws instead of protecting, and
        // the carrier is never called.
        assertThat(result.blockingSignature().outcome()).isEqualTo("IllegalReturnTypeException");
        assertThat(result.blockingSignature().elapsedMillis()).isLessThan(500L);
        assertThat(result.blockingSignature().requestsServedByTheCarrier()).isZero();

        // On the async signature the caller gives up at the limit - and the
        // carrier serves the request all the same.
        assertThat(result.asyncSignature().outcome()).isEqualTo("TimeoutException");
        assertThat(result.asyncSignature().elapsedMillis()).isLessThan(1_500L);
        assertThat(result.asyncSignature().requestsServedByTheCarrier()).isEqualTo(1);
    }

    @Test
    @DisplayName("the timeout frees the caller; with cancelRunningFuture off the worker stays busy")
    void cancelRunningFuture() {
        CancelRunningFutureExperiment.Result result =
                (CancelRunningFutureExperiment.Result) cancelRunningFuture.run(Map.of());

        assertThat(result.cancelling().callerFreedAfterMillis()).isLessThan(1_500L);
        assertThat(result.notCancelling().callerFreedAfterMillis()).isLessThan(1_500L);
        assertThat(result.notCancelling().workerFreedAfterExtraMillis())
                .isGreaterThan(result.cancelling().workerFreedAfterExtraMillis());
        // The carrier served the request either way: the caller gave up, the
        // work did not stop.
        assertThat(result.cancelling().requestsServedByTheCarrier()).isEqualTo(1);
    }

    @Test
    @DisplayName("jitter spreads the retry wave: fewer attempts land in the same window")
    void retryJitter() {
        RetryJitterExperiment.Result result = (RetryJitterExperiment.Result) retryJitter.run(Map.of());

        assertThat(result.withoutJitter().attemptsReachingTheCarrier())
                .isEqualTo(result.clients() * result.attemptsPerClient());
        assertThat(result.withoutJitter().retryAttempts())
                .isEqualTo(result.clients() * (result.attemptsPerClient() - 1));
        assertThat(result.withJitter().peakRetriesPerBucket())
                .isLessThan(result.withoutJitter().peakRetriesPerBucket());
    }

    @Test
    @DisplayName("the recovery costs the wait plus the probes, and one probe decides alone")
    void halfOpenRecovery() {
        HalfOpenRecoveryExperiment.Result result =
                (HalfOpenRecoveryExperiment.Result) halfOpenRecovery.run(Map.of());

        assertThat(result.recovery().stateAfterOutage()).isEqualTo("OPEN");
        assertThat(result.recovery().stateAfterRecovery()).isEqualTo("CLOSED");
        assertThat(result.recovery().recoveryMillis()).isGreaterThanOrEqualTo(900L);
        assertThat(result.recovery().callsRefusedWhileCarrierWasHealthy()).isPositive();

        assertThat(result.onePermittedCall().probesSentToASickService()).isEqualTo(10);
        assertThat(result.threePermittedCalls().probesSentToASickService()).isEqualTo(30);
        // One failure in three is below the threshold, so three probes always
        // close; a single probe reopens whenever it draws the bad call.
        assertThat(result.threePermittedCalls().closedOnTheProbes()).isEqualTo(10);
        assertThat(result.onePermittedCall().reopened()).isPositive();
    }

    @Test
    @DisplayName("without a bulkhead a slow dependency drags down the endpoint that uses nothing")
    void bulkheadIsolation() {
        BulkheadIsolationExperiment.Result result = (BulkheadIsolationExperiment.Result) bulkheadIsolation
                .run(Map.of("selfBaseUrl", selfBaseUrl()));

        assertThat(result.withBulkhead().neighbourP95Millis())
                .isLessThan(result.withoutBulkhead().neighbourP95Millis());
        assertThat(result.withBulkhead().neighbourAnswered())
                .isGreaterThan(result.withoutBulkhead().neighbourAnswered() * 5);
    }
}
