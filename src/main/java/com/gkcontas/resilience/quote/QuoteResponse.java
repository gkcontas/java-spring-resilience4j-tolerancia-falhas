package com.gkcontas.resilience.quote;

/**
 * @param source where the answer came from, so a degraded answer is visible to
 *               the caller instead of looking like a real quote
 */
public record QuoteResponse(String sku, String carrier, long amountCents, int etaDays, QuoteSource source) {
}
