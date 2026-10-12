package com.gkcontas.resilience.shipment;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record ShipmentRequest(
        @NotBlank(message = "sku must not be blank") String sku,
        @Min(value = 1, message = "quantity must be at least 1") int quantity) {
}
