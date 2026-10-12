package com.gkcontas.resilience.quote;

import java.util.concurrent.CompletableFuture;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/quotes")
public class QuoteController {

    private final QuoteService quoteService;
    private final AsyncQuoteService asyncQuoteService;

    public QuoteController(QuoteService quoteService, AsyncQuoteService asyncQuoteService) {
        this.quoteService = quoteService;
        this.asyncQuoteService = asyncQuoteService;
    }

    @GetMapping("/{sku}")
    public QuoteResponse quote(@PathVariable String sku) {
        return quoteService.quote(sku);
    }

    @GetMapping("/unprotected/{sku}")
    public QuoteResponse unprotected(@PathVariable String sku) {
        return quoteService.quoteUnprotected(sku);
    }

    @GetMapping("/inner-fallback/{sku}")
    public QuoteResponse innerFallback(@PathVariable String sku) {
        return quoteService.quoteWithInnerFallback(sku);
    }

    @GetMapping("/broad-fallback/{sku}")
    public QuoteResponse broadFallback(@PathVariable String sku,
            @RequestParam(defaultValue = "false") boolean plantBug) {
        return quoteService.quoteWithBroadFallback(sku, plantBug);
    }

    @GetMapping("/async/{sku}")
    public CompletableFuture<QuoteResponse> async(@PathVariable String sku) {
        return asyncQuoteService.quoteAsync(sku);
    }

    @GetMapping("/blocking-time-limiter/{sku}")
    public QuoteResponse blockingTimeLimiter(@PathVariable String sku) {
        return asyncQuoteService.quoteBlocking(sku);
    }
}
