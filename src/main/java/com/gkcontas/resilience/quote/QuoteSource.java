package com.gkcontas.resilience.quote;

public enum QuoteSource {
    /** Answered by the carrier service. */
    CARRIER,
    /** Answered by the fallback: a cached table, not a live quote. */
    FALLBACK
}
