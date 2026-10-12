package com.gkcontas.resilience.experiment;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.stereotype.Component;

import com.gkcontas.resilience.carrier.CarrierClient;
import com.gkcontas.resilience.faults.FaultInjector;

/**
 * A time limiter frees the CALLER. It does not cancel the work.
 *
 * <p>What it does is exactly {@code future.get(timeout)} plus, when
 * cancelRunningFuture is on, {@code future.cancel(true)}. With it off the
 * worker thread stays busy for the whole downstream call, so the pool the
 * timeout was supposed to protect is the one still blocked. With it on the
 * thread comes back - and the carrier serves the request anyway, because an
 * interrupt on this side is not a cancellation on theirs.
 */
@Component
public class CancelRunningFutureExperiment implements Experiment {

    private static final Duration CARRIER_DELAY = Duration.ofMillis(2000);
    private static final long LIMIT_MILLIS = 500;

    private final CarrierClient client;
    private final FaultInjector faults;

    public CancelRunningFutureExperiment(CarrierClient client, FaultInjector faults) {
        this.client = client;
        this.faults = faults;
    }

    @Override
    public String name() {
        return "cancel-running-future";
    }

    @Override
    public Object run(Map<String, String> params) {
        faults.slow(CARRIER_DELAY);
        Arm cancelling = measure(true);
        faults.slow(CARRIER_DELAY);
        Arm notCancelling = measure(false);
        return new Result(CARRIER_DELAY.toMillis(), LIMIT_MILLIS, cancelling, notCancelling);
    }

    private Arm measure(boolean cancelRunningFuture) {
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            long start = System.nanoTime();
            Future<?> call = worker.submit(() -> client.quote("SKU-CANCEL"));

            String outcome = "answered";
            try {
                call.get(LIMIT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                outcome = "TimeoutException";
                if (cancelRunningFuture) {
                    call.cancel(true);
                }
            } catch (Exception e) {
                outcome = e.getClass().getSimpleName();
            }
            long callerFreed = millis(start);

            // The marker only runs once the single worker thread is free again.
            long markerStart = System.nanoTime();
            awaitQuietly(worker.submit(() -> "marker"));
            long workerFreed = millis(markerStart);

            int served = faults.requestCount("GET", "/carrier/quotes/([^/]+)");
            return new Arm(cancelRunningFuture, callerFreed, outcome, workerFreed, served);
        }
    }

    private static void awaitQuietly(Future<?> future) {
        try {
            future.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // nothing to assert here, the wait itself is the measurement
        }
    }

    private static long millis(long startNanos) {
        return Math.round((System.nanoTime() - startNanos) / 1_000_000.0);
    }

    public record Arm(boolean cancelRunningFuture, long callerFreedAfterMillis, String callerOutcome,
            long workerFreedAfterExtraMillis, int requestsServedByTheCarrier) {
    }

    public record Result(long carrierDelayMillis, long timeLimitMillis, Arm cancelling, Arm notCancelling) {
    }
}
