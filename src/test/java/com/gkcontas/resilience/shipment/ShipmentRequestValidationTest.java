package com.gkcontas.resilience.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * The messages are explicit on purpose: the default ones are resolved by the
 * JVM locale, and an English contract answering in Portuguese is a bug nobody
 * notices until a client parses it.
 */
class ShipmentRequestValidationTest {

    private static final Validator VALIDATOR;

    static {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            VALIDATOR = factory.getValidator();
        }
    }

    @Test
    @DisplayName("a valid request has no violation")
    void valid() {
        assertThat(VALIDATOR.validate(new ShipmentRequest("SKU-1", 2))).isEmpty();
    }

    @Test
    @DisplayName("a blank sku is rejected with the declared English message")
    void blankSku() {
        Set<ConstraintViolation<ShipmentRequest>> violations = VALIDATOR.validate(new ShipmentRequest("  ", 2));

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).isEqualTo("sku must not be blank");
    }

    @Test
    @DisplayName("quantity below one is rejected with the declared English message")
    void quantityBelowOne() {
        Set<ConstraintViolation<ShipmentRequest>> violations = VALIDATOR.validate(new ShipmentRequest("SKU-1", 0));

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).isEqualTo("quantity must be at least 1");
    }
}
