package com.gkcontas.resilience.shipment;

import org.springframework.stereotype.Service;

import com.gkcontas.resilience.carrier.CarrierClient;
import com.gkcontas.resilience.carrier.ShipmentReceipt;

import io.github.resilience4j.retry.annotation.Retry;

/**
 * Registering a shipment is NOT idempotent: the carrier mints a receipt id per
 * call. A retry here cannot tell "the request never arrived" from "the answer
 * never came back", so a 500 after the shipment was already recorded turns one
 * logical call into three shipments.
 *
 * <p>Both versions are kept so the duplication can be counted at the other end
 * instead of argued about.
 */
@Service
public class ShipmentService {

    private final CarrierClient client;

    public ShipmentService(CarrierClient client) {
        this.client = client;
    }

    @Retry(name = "carrier")
    public ShipmentReceipt registerWithRetry(String sku, int quantity) {
        return client.registerShipment(sku, quantity);
    }

    public ShipmentReceipt registerWithoutRetry(String sku, int quantity) {
        return client.registerShipment(sku, quantity);
    }
}
