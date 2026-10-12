package com.gkcontas.resilience.experiment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.gkcontas.resilience.faults.FaultInjector;

/**
 * The classic outage: one slow dependency takes down endpoints that do not even
 * use it. A hundred and fifty concurrent quote requests against a carrier that
 * answers in a second and a half park every thread of the container, and
 * /catalog - which calls nothing - stops answering.
 *
 * <p>A bulkhead of ten does not make the carrier faster. It caps how much of
 * the container the carrier is allowed to own, and sheds the rest into the
 * fallback. The number that matters is the latency of the neighbour.
 *
 * <p>This experiment goes through the real HTTP port, because the resource it
 * is about is the servlet container's thread pool.
 */
@Component
public class BulkheadIsolationExperiment implements Experiment {

    // 150 requests against 50 container threads plus a queue of 100: the load
    // fits the server exactly and keeps it saturated for the whole probe
    // window, which is what makes the neighbour's latency readable.
    private static final int LOAD = 150;
    private static final Duration CARRIER_DELAY = Duration.ofMillis(1500);

    private final FaultInjector faults;
    private final String defaultBaseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();

    public BulkheadIsolationExperiment(FaultInjector faults,
            @Value("${experiments.self-base-url:http://localhost:8080}") String defaultBaseUrl) {
        this.faults = faults;
        this.defaultBaseUrl = defaultBaseUrl;
    }

    @Override
    public String name() {
        return "bulkhead-isolation";
    }

    @Override
    public Object run(Map<String, String> params) {
        String baseUrl = params.getOrDefault("selfBaseUrl", defaultBaseUrl);
        faults.slow(CARRIER_DELAY);
        Arm unprotected = measure(baseUrl, "/quotes/unprotected/SKU-NOISY");
        Arm bulkheaded = measure(baseUrl, "/quotes/SKU-NOISY");
        return new Result(LOAD, CARRIER_DELAY.toMillis(), unprotected, bulkheaded);
    }

    private Arm measure(String baseUrl, String loadPath) {
        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch started = new CountDownLatch(1);
        List<Long> neighbour = new ArrayList<>();
        int failedProbes;

        try (ExecutorService load = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < LOAD; i++) {
                load.submit(() -> {
                    started.countDown();
                    get(baseUrl + loadPath, Duration.ofSeconds(20));
                });
            }
            awaitQuietly(started);
            // Give the load a moment to actually fill the container.
            sleep(300);
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            int failed = 0;
            while (System.nanoTime() < deadline) {
                long start = System.nanoTime();
                // Only an answered probe is a sample. A connection the server
                // never accepted would otherwise be recorded as a fast one.
                if (get(baseUrl + "/catalog/SKU-QUIET", Duration.ofSeconds(20))) {
                    neighbour.add(System.nanoTime() - start);
                } else {
                    failed++;
                }
            }
            running.set(false);
            failedProbes = failed;
        }

        Stats stats = Stats.ofNanos(neighbour);
        return new Arm(loadPath, stats.samples(), failedProbes, stats.p50Millis(), stats.p95Millis(),
                stats.maxMillis());
    }

    private boolean get(String url, Duration timeout) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            // A request the saturated server never accepted is part of the
            // picture, not a crash.
            return false;
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public record Arm(String loadPath, int neighbourAnswered, int neighbourFailed, double neighbourP50Millis,
            double neighbourP95Millis, double neighbourMaxMillis) {
    }

    public record Result(int concurrentLoad, long carrierDelayMillis, Arm withoutBulkhead, Arm withBulkhead) {
    }
}
