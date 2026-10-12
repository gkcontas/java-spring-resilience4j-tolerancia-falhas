package com.gkcontas.resilience.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Timeouts live here and not in a Resilience4j policy on purpose: a TimeLimiter
 * bounds how long the caller waits, never how long the socket stays open. The
 * connection and the read timeout are the only things that free the resource.
 */
@ConfigurationProperties(prefix = "carrier")
public record CarrierProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {
}
