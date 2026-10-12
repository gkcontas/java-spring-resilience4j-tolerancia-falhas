package com.gkcontas.resilience.carrier;

/**
 * What the carrier service answers when a shipment is registered. The receipt
 * id is generated on their side, which is what makes the call non idempotent:
 * two calls produce two shipments.
 */
public record ShipmentReceipt(String receiptId, String sku, int quantity) {
}
