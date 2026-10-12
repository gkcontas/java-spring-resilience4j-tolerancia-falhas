package com.gkcontas.resilience.carrier;

/**
 * What the carrier service answers for a quote request.
 */
public record CarrierQuote(String sku, String carrier, long amountCents, int etaDays) {
}
