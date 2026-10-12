package com.gkcontas.resilience.experiment;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.gkcontas.resilience.carrier.CarrierClient;
import com.gkcontas.resilience.carrier.CarrierQuote;
import com.gkcontas.resilience.carrier.CarrierUnavailableException;
import com.gkcontas.resilience.faults.FaultInjector;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

/**
 * Exponential backoff without jitter does not spread load, it schedules it.
 * Every client that failed on the same outage waits the same two hundred
 * milliseconds and comes back together - the service gets its traffic in
 * synchronised waves, which is the worst shape to recover under.
 *
 * <p>Measured at the other end: how many of the sixty attempts land inside the
 * same fifty millisecond window.
 */
@Component
public class RetryJitterExperiment implements Experiment {

    private static final int CLIENTS = 20;
    private static final int ATTEMPTS = 3;
    private static final long BUCKET_MILLIS = 50;

    private final CarrierClient client;
    private final FaultInjector faults;

    public RetryJitterExperiment(CarrierClient client, FaultInjector faults) {
        this.client = client;
        this.faults = faults;
    }

    @Override
    public String name() {
        return "retry-jitter";
    }

    @Override
    public Object run(Map<String, String> params) {
        faults.serverError();
        Arm noJitter = measure(0);
        faults.serverError();
        Arm jitter = measure(0.5);
        return new Result(CLIENTS, ATTEMPTS, BUCKET_MILLIS, noJitter, jitter);
    }

    private Arm measure(double randomizationFactor) {
        faults.resetJournal();
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(ATTEMPTS)
                .intervalFunction(randomizationFactor == 0
                        ? IntervalFunction.ofExponentialBackoff(Duration.ofMillis(200), 2)
                        : IntervalFunction.ofExponentialRandomBackoff(Duration.ofMillis(200), 2,
                                randomizationFactor))
                .retryExceptions(CarrierUnavailableException.class)
                .build();

        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CLIENTS);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < CLIENTS; i++) {
                Retry retry = Retry.of("jitter-" + randomizationFactor + "-" + i, config);
                Supplier<CarrierQuote> decorated = Retry.decorateSupplier(retry,
                        () -> client.quote("SKU-JITTER"));
                pool.submit(() -> {
                    try {
                        gate.await();
                        decorated.get();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (RuntimeException e) {
                        // every attempt fails on purpose
                    } finally {
                        done.countDown();
                    }
                });
            }
            gate.countDown();
            awaitQuietly(done);
        }

        List<Long> arrivals = faults.requestTimestamps("GET", "/carrier/quotes/([^/]+)");
        // The first wave is simultaneous by construction - every client starts
        // at the gate. What backoff decides is where the RETRIES land, so the
        // peak is measured on them.
        List<Long> retries = arrivals.size() > CLIENTS
                ? arrivals.subList(CLIENTS, arrivals.size())
                : List.of();
        return new Arm(randomizationFactor, arrivals.size(), retries.size(), peakPerBucket(retries),
                spread(retries));
    }

    private static int peakPerBucket(List<Long> arrivals) {
        if (arrivals.isEmpty()) {
            return 0;
        }
        long first = arrivals.get(0);
        Map<Long, Integer> buckets = new java.util.HashMap<>();
        for (long arrival : arrivals) {
            buckets.merge((arrival - first) / BUCKET_MILLIS, 1, Integer::sum);
        }
        return buckets.values().stream().max(Integer::compareTo).orElse(0);
    }

    private static long spread(List<Long> arrivals) {
        return arrivals.isEmpty() ? 0 : arrivals.get(arrivals.size() - 1) - arrivals.get(0);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public record Arm(double randomizationFactor, int attemptsReachingTheCarrier, int retryAttempts,
            int peakRetriesPerBucket, long spreadMillis) {
    }

    public record Result(int clients, int attemptsPerClient, long bucketMillis, Arm withoutJitter, Arm withJitter) {
    }
}
