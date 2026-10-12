package com.gkcontas.resilience.quote;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import org.springframework.stereotype.Service;

import com.gkcontas.resilience.carrier.CarrierClient;
import com.gkcontas.resilience.carrier.CarrierQuote;

import io.github.resilience4j.timelimiter.annotation.TimeLimiter;

/**
 * TimeLimiter only applies to a method that returns a CompletionStage. On a
 * blocking signature the aspect has nothing to time out and the annotation is
 * ignored in silence - the call takes as long as the socket takes.
 *
 * <p>And even on the async signature the limit bounds the WAIT, not the WORK:
 * the thread doing the HTTP call stays busy until the read timeout fires.
 */
@Service
public class AsyncQuoteService {

    private final CarrierClient client;
    private final ExecutorService executor;

    public AsyncQuoteService(CarrierClient client, ExecutorService quoteExecutor) {
        this.client = client;
        this.executor = quoteExecutor;
    }

    @TimeLimiter(name = QuoteService.CARRIER)
    public CompletableFuture<QuoteResponse> quoteAsync(String sku) {
        return CompletableFuture.supplyAsync(() -> toResponse(client.quote(sku)), executor);
    }

    /** The same annotation on a blocking return type: inert. */
    @TimeLimiter(name = QuoteService.CARRIER)
    public QuoteResponse quoteBlocking(String sku) {
        return toResponse(client.quote(sku));
    }

    private QuoteResponse toResponse(CarrierQuote quote) {
        return new QuoteResponse(quote.sku(), quote.carrier(), quote.amountCents(), quote.etaDays(),
                QuoteSource.CARRIER);
    }
}
