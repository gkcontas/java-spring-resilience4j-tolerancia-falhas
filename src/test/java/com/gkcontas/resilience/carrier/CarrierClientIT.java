package com.gkcontas.resilience.carrier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.gkcontas.resilience.support.IntegrationTestBase;

/**
 * The classification every policy above depends on, asserted against a real
 * socket. A client that cannot tell a 404 from a dropped connection makes
 * recordExceptions impossible to configure.
 */
class CarrierClientIT extends IntegrationTestBase {

    @Autowired
    CarrierClient client;

    @Test
    @DisplayName("a healthy carrier answers the quote, with the sku echoed back")
    void healthyQuote() {
        CarrierQuote quote = client.quote("SKU-100");

        assertThat(quote.sku()).isEqualTo("SKU-100");
        assertThat(quote.carrier()).isEqualTo("SwiftShip");
        assertThat(quote.amountCents()).isEqualTo(4990L);
    }

    @Test
    @DisplayName("404 becomes a business exception, not a technical one")
    void notFoundIsBusiness() {
        faults.notFound();

        assertThatThrownBy(() -> client.quote("SKU-GHOST"))
                .isInstanceOf(QuoteNotFoundException.class)
                .hasMessageContaining("SKU-GHOST");
    }

    @Test
    @DisplayName("500 becomes a technical exception")
    void serverErrorIsTechnical() {
        faults.serverError();

        assertThatThrownBy(() -> client.quote("SKU-100"))
                .isInstanceOf(CarrierUnavailableException.class)
                .hasMessageContaining("500");
    }

    @Test
    @DisplayName("a dropped connection becomes a technical exception, with no status to read")
    void connectionResetIsTechnical() {
        faults.connectionReset();

        assertThatThrownBy(() -> client.quote("SKU-100"))
                .isInstanceOf(CarrierUnavailableException.class);
    }

    @Test
    @DisplayName("a carrier slower than the read timeout becomes a technical exception, at the timeout")
    void readTimeoutIsTechnical() {
        faults.slow(Duration.ofSeconds(6));

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.quote("SKU-100"))
                .isInstanceOf(CarrierUnavailableException.class);
        long elapsed = (System.nanoTime() - start) / 1_000_000;

        // The read timeout is 3s: the call must come back around there and not
        // when the carrier finally answers.
        assertThat(elapsed).isBetween(2_500L, 5_000L);
    }

    @Test
    @DisplayName("the shipment call is answered with a receipt minted on the carrier side")
    void shipmentReceipt() {
        ShipmentReceipt receipt = client.registerShipment("SKU-100", 3);

        assertThat(receipt.receiptId()).isNotBlank();
        assertThat(receipt.sku()).isEqualTo("SKU-100");
        assertThat(receipt.quantity()).isEqualTo(3);
    }

    @Test
    @DisplayName("two shipment calls mint two different receipts, which is what makes a retry unsafe")
    void shipmentIsNotIdempotent() {
        ShipmentReceipt first = client.registerShipment("SKU-100", 1);
        ShipmentReceipt second = client.registerShipment("SKU-100", 1);

        assertThat(first.receiptId()).isNotEqualTo(second.receiptId());
        assertThat(faults.requestCount("POST", "/carrier/shipments")).isEqualTo(2);
    }
}
