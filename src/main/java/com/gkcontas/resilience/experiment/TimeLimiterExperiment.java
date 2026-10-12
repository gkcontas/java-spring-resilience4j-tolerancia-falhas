package com.gkcontas.resilience.experiment;

import java.time.Duration;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.gkcontas.resilience.faults.FaultInjector;
import com.gkcontas.resilience.quote.AsyncQuoteService;
import com.gkcontas.resilience.quote.QuoteService;

/**
 * A TimeLimiter is not a read timeout.
 *
 * <p>Three arms, same carrier taking two seconds. Without any limiter the call
 * costs the whole delay. With {@code @TimeLimiter} on a blocking signature the
 * aspect refuses the method outright - at the first call, in production, not at
 * startup - because it only knows how to wrap a CompletionStage. On the async
 * signature it does what it says, and the carrier serves the request anyway:
 * the limit bounds the WAIT, never the WORK.
 */
@Component
public class TimeLimiterExperiment implements Experiment {

    private static final Duration CARRIER_DELAY = Duration.ofMillis(2000);
    private static final long LIMIT_MILLIS = 500;

    private final AsyncQuoteService asyncService;
    private final QuoteService service;
    private final FaultInjector faults;

    public TimeLimiterExperiment(AsyncQuoteService asyncService, QuoteService service, FaultInjector faults) {
        this.asyncService = asyncService;
        this.service = service;
        this.faults = faults;
    }

    @Override
    public String name() {
        return "time-limiter";
    }

    @Override
    public Object run(Map<String, String> params) {
        return new Result(CARRIER_DELAY.toMillis(), LIMIT_MILLIS,
                measure(() -> service.quoteUnprotected("SKU-TL"), "no limiter"),
                measure(() -> asyncService.quoteBlocking("SKU-TL"), "@TimeLimiter on a blocking signature"),
                measure(() -> asyncService.quoteAsync("SKU-TL").join(), "@TimeLimiter on CompletableFuture"));
    }

    private Arm measure(Runnable call, String description) {
        faults.slow(CARRIER_DELAY);
        long start = System.nanoTime();
        String outcome = "answered";
        try {
            call.run();
        } catch (RuntimeException e) {
            Throwable cause = e instanceof java.util.concurrent.CompletionException && e.getCause() != null
                    ? e.getCause()
                    : e;
            outcome = cause.getClass().getSimpleName();
        }
        long elapsed = Math.round((System.nanoTime() - start) / 1_000_000.0);
        return new Arm(description, elapsed, outcome, faults.requestCount("GET", "/carrier/quotes/([^/]+)"));
    }

    public record Arm(String configuration, long elapsedMillis, String outcome, int requestsServedByTheCarrier) {
    }

    public record Result(long carrierDelayMillis, long timeLimitMillis, Arm withoutLimiter,
            Arm blockingSignature, Arm asyncSignature) {
    }
}
