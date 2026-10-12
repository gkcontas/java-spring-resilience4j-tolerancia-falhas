package com.gkcontas.resilience.quote;

import org.springframework.stereotype.Service;

import com.gkcontas.resilience.carrier.CarrierClient;
import com.gkcontas.resilience.carrier.CarrierQuote;
import com.gkcontas.resilience.carrier.CarrierUnavailableException;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;

/**
 * The protected path.
 *
 * <p>The annotations compose in a fixed order, from the outside in:
 * Retry, CircuitBreaker, RateLimiter, TimeLimiter, Bulkhead. So the circuit
 * breaker here records every attempt, not one result per logical call - which
 * is a decision, not a detail. {@code DecoratorOrderExperiment} measures both
 * compositions.
 *
 * <p>The fallback sits on the OUTERMOST annotation. On an inner one it answers
 * before the outer policy ever sees a failure: a fallback on the circuit
 * breaker makes the retry above it retry nothing, because the call it wraps
 * returns normally.
 */
@Slf4j
@Service
public class QuoteService {

    static final String CARRIER = "carrier";

    private final CarrierClient client;

    public QuoteService(CarrierClient client) {
        this.client = client;
    }

    @Retry(name = CARRIER, fallbackMethod = "unavailableFallback")
    @CircuitBreaker(name = CARRIER)
    @Bulkhead(name = CARRIER)
    public QuoteResponse quote(String sku) {
        return toResponse(client.quote(sku));
    }

    /** No policy at all, kept as the baseline every measurement compares against. */
    public QuoteResponse quoteUnprotected(String sku) {
        return toResponse(client.quote(sku));
    }

    /**
     * Same policies, fallback on the inner annotation. The retry above never
     * fires, because the circuit breaker hands it a successful fallback.
     */
    @Retry(name = CARRIER)
    @CircuitBreaker(name = CARRIER, fallbackMethod = "unavailableFallback")
    public QuoteResponse quoteWithInnerFallback(String sku) {
        return toResponse(client.quote(sku));
    }

    /**
     * The broad fallback. It catches Throwable, so a NullPointerException in
     * this very method stops being an error and starts being a degraded answer
     * - with a 200 and no stack trace anywhere.
     */
    @CircuitBreaker(name = CARRIER, fallbackMethod = "anythingFallback")
    public QuoteResponse quoteWithBroadFallback(String sku, boolean plantBug) {
        if (plantBug) {
            String nothing = null;
            return toResponse(client.quote(nothing.trim()));
        }
        return toResponse(client.quote(sku));
    }

    /**
     * Same planted bug, fallback typed to the failure it knows how to replace.
     * The NullPointerException matches no signature, so it propagates and shows
     * up as the 500 it is.
     */
    @CircuitBreaker(name = CARRIER, fallbackMethod = "narrowFallback")
    public QuoteResponse quoteWithNarrowFallback(String sku, boolean plantBug) {
        if (plantBug) {
            String nothing = null;
            return toResponse(client.quote(nothing.trim()));
        }
        return toResponse(client.quote(sku));
    }

    // The fallbacks are selected by the type of the thrown exception: the most
    // specific signature wins, and an exception no signature accepts keeps
    // propagating. That is what keeps a bug from being answered with a 200.

    QuoteResponse unavailableFallback(String sku, CarrierUnavailableException e) {
        log.warn("carrier unavailable for sku {}: {}", sku, e.getMessage());
        return cached(sku);
    }

    QuoteResponse unavailableFallback(String sku, CallNotPermittedException e) {
        log.warn("circuit open, not calling the carrier for sku {}", sku);
        return cached(sku);
    }

    QuoteResponse unavailableFallback(String sku, BulkheadFullException e) {
        log.warn("bulkhead full, shedding the quote for sku {}", sku);
        return cached(sku);
    }

    @SuppressWarnings("unused")
    QuoteResponse anythingFallback(String sku, boolean plantBug, Throwable t) {
        return cached(sku);
    }

    @SuppressWarnings("unused")
    QuoteResponse narrowFallback(String sku, boolean plantBug, CarrierUnavailableException e) {
        return cached(sku);
    }

    private QuoteResponse cached(String sku) {
        return new QuoteResponse(sku, "fallback-table", 2990L, 7, QuoteSource.FALLBACK);
    }

    private QuoteResponse toResponse(CarrierQuote quote) {
        return new QuoteResponse(quote.sku(), quote.carrier(), quote.amountCents(), quote.etaDays(),
                QuoteSource.CARRIER);
    }
}
