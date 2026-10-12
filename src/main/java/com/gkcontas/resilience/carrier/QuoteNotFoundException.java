package com.gkcontas.resilience.carrier;

/**
 * The carrier answered 404: this SKU has no quote. It is a business outcome of
 * a service that is working, so counting it as a circuit breaker failure opens
 * the circuit for every other SKU while nothing is wrong.
 */
public class QuoteNotFoundException extends RuntimeException {

    private final String sku;

    public QuoteNotFoundException(String sku) {
        super("no carrier quote for sku " + sku);
        this.sku = sku;
    }

    public String getSku() {
        return sku;
    }
}
