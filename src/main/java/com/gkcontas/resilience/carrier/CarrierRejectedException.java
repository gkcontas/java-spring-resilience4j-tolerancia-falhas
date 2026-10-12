package com.gkcontas.resilience.carrier;

/**
 * The carrier answered 4xx other than 404: the request itself is wrong.
 * Retrying it is pure waste - the next attempt carries the same payload.
 */
public class CarrierRejectedException extends RuntimeException {

    private final int status;

    public CarrierRejectedException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
