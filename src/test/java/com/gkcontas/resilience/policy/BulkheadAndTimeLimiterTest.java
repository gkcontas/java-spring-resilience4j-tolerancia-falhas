package com.gkcontas.resilience.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;

class BulkheadAndTimeLimiterTest {

    @Test
    @DisplayName("a bulkhead of three admits three concurrent calls and sheds the rest immediately")
    void bulkheadShedsBeyondItsLimit() throws Exception {
        Bulkhead bulkhead = Bulkhead.of("shed", BulkheadConfig.custom()
                .maxConcurrentCalls(3)
                .maxWaitDuration(Duration.ZERO)
                .build());
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch inside = new CountDownLatch(3);
        AtomicInteger shed = new AtomicInteger();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 10; i++) {
                pool.submit(() -> {
                    try {
                        bulkhead.executeRunnable(() -> {
                            inside.countDown();
                            await(hold);
                        });
                    } catch (BulkheadFullException e) {
                        shed.incrementAndGet();
                    }
                });
            }
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
            // Everything that is not one of the three holders is shed at once.
            Thread.sleep(200);
            assertThat(shed.get()).isEqualTo(7);
            assertThat(bulkhead.getMetrics().getAvailableConcurrentCalls()).isZero();
            hold.countDown();
        }
    }

    @Test
    @DisplayName("the shed call is rejected without ever running")
    void shedCallDoesNotRun() {
        Bulkhead bulkhead = Bulkhead.of("never-runs", BulkheadConfig.custom()
                .maxConcurrentCalls(1)
                .maxWaitDuration(Duration.ZERO)
                .build());
        AtomicInteger runs = new AtomicInteger();
        bulkhead.tryAcquirePermission();

        assertThatThrownBy(() -> bulkhead.executeRunnable(runs::incrementAndGet))
                .isInstanceOf(BulkheadFullException.class);
        assertThat(runs.get()).isZero();
    }

    @Test
    @DisplayName("maxWaitDuration turns shedding into queueing, and burns the caller's thread while it waits")
    void maxWaitDurationQueues() {
        Bulkhead bulkhead = Bulkhead.of("queue", BulkheadConfig.custom()
                .maxConcurrentCalls(1)
                .maxWaitDuration(Duration.ofMillis(300))
                .build());
        bulkhead.tryAcquirePermission();

        long start = System.nanoTime();
        assertThatThrownBy(() -> bulkhead.executeRunnable(() -> {
        })).isInstanceOf(BulkheadFullException.class);
        long waited = (System.nanoTime() - start) / 1_000_000;

        assertThat(waited).isGreaterThanOrEqualTo(250);
    }

    @Test
    @DisplayName("the time limiter gives up on the future at the configured limit")
    void timeLimiterTimesOut() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        TimeLimiter limiter = TimeLimiter.of("limit", TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(200))
                .cancelRunningFuture(true)
                .build());

        try {
            assertThatThrownBy(() -> limiter.executeFutureSupplier(() -> CompletableFuture.supplyAsync(() -> {
                sleep(2000);
                return "late";
            }))).isInstanceOf(TimeoutException.class);
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    @DisplayName("a future that answers inside the limit comes through untouched")
    void timeLimiterPassesFastCalls() throws Exception {
        TimeLimiter limiter = TimeLimiter.of("fast", TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(500))
                .build());

        String result = limiter.executeFutureSupplier(() -> CompletableFuture.completedFuture("ok"));

        assertThat(result).isEqualTo("ok");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
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
}
