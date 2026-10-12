package com.gkcontas.resilience.api;

public record ErrorResponse(int status, String error, String message) {
}
