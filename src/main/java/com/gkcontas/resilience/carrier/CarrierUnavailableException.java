package com.gkcontas.resilience.carrier;

/**
 * A technical failure of the carrier service: 5xx, read timeout, connection
 * reset. This is the only family that should move a circuit breaker.
 */
public class CarrierUnavailableException extends RuntimeException {

    public CarrierUnavailableException(String message) {
        super(message);
    }

    public CarrierUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
