package com.gkcontas.resilience.api;

import java.util.concurrent.TimeoutException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.gkcontas.resilience.carrier.CarrierRejectedException;
import com.gkcontas.resilience.carrier.CarrierUnavailableException;
import com.gkcontas.resilience.carrier.QuoteNotFoundException;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

/**
 * Every policy has a status of its own. Collapsing them into one 500 is how a
 * team ends up unable to tell "the dependency is down" from "we shed this call
 * on purpose".
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(QuoteNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(QuoteNotFoundException e) {
        return body(HttpStatus.NOT_FOUND, "quote_not_found", e.getMessage());
    }

    @ExceptionHandler(CarrierRejectedException.class)
    ResponseEntity<ErrorResponse> rejected(CarrierRejectedException e) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, "carrier_rejected", e.getMessage());
    }

    @ExceptionHandler(CarrierUnavailableException.class)
    ResponseEntity<ErrorResponse> unavailable(CarrierUnavailableException e) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, "carrier_unavailable", e.getMessage());
    }

    /**
     * The open circuit answers with the same status as the dependency being
     * down, plus Retry-After - the client has no reason to come back before the
     * breaker is willing to try again.
     */
    @ExceptionHandler(CallNotPermittedException.class)
    ResponseEntity<ErrorResponse> circuitOpen(CallNotPermittedException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(new ErrorResponse(HttpStatus.SERVICE_UNAVAILABLE.value(), "circuit_open",
                        "the carrier circuit is open"));
    }

    /** Load shedding is a 429, not a 503: the dependency may be perfectly fine. */
    @ExceptionHandler(BulkheadFullException.class)
    ResponseEntity<ErrorResponse> bulkheadFull(BulkheadFullException e) {
        return body(HttpStatus.TOO_MANY_REQUESTS, "bulkhead_full", "too many concurrent carrier calls");
    }

    @ExceptionHandler(TimeoutException.class)
    ResponseEntity<ErrorResponse> timeout(TimeoutException e) {
        return body(HttpStatus.GATEWAY_TIMEOUT, "carrier_timeout", "the carrier did not answer in time");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> invalid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("invalid request");
        return body(HttpStatus.BAD_REQUEST, "invalid_request", message);
    }

    private static ResponseEntity<ErrorResponse> body(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(status.value(), error, message));
    }
}
