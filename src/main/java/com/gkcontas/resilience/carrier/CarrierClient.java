package com.gkcontas.resilience.carrier;

import java.io.IOException;
import java.util.Map;

import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The raw call to the carrier. No policy lives here: this class only turns a
 * transport outcome into an exception type, and that classification is what
 * every policy above it depends on. A client that throws one single
 * RuntimeException for 404 and for read timeout makes recordExceptions
 * impossible to write.
 */
@Component
public class CarrierClient {

    private final RestClient restClient;

    public CarrierClient(RestClient carrierRestClient) {
        this.restClient = carrierRestClient;
    }

    public CarrierQuote quote(String sku) {
        try {
            return restClient.get()
                    .uri("/carrier/quotes/{sku}", sku)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        if (response.getStatusCode().value() == 404) {
                            throw new QuoteNotFoundException(sku);
                        }
                        throw new CarrierRejectedException(response.getStatusCode().value(),
                                "carrier rejected the quote request for sku " + sku);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                        throw new CarrierUnavailableException(
                                "carrier answered " + response.getStatusCode().value() + " for sku " + sku);
                    })
                    .body(CarrierQuote.class);
        } catch (ResourceAccessException e) {
            // Read timeout, connection reset and connect timeout all land here.
            throw new CarrierUnavailableException("carrier transport failure for sku " + sku, e);
        } catch (IllegalStateException e) {
            if (e.getCause() instanceof IOException) {
                throw new CarrierUnavailableException("carrier transport failure for sku " + sku, e);
            }
            throw e;
        }
    }

    public ShipmentReceipt registerShipment(String sku, int quantity) {
        try {
            return restClient.post()
                    .uri("/carrier/shipments")
                    .body(Map.of("sku", sku, "quantity", quantity))
                    .retrieve()
                    .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                        throw new CarrierUnavailableException(
                                "carrier answered " + response.getStatusCode().value() + " for the shipment");
                    })
                    .body(ShipmentReceipt.class);
        } catch (ResourceAccessException e) {
            throw new CarrierUnavailableException("carrier transport failure on shipment", e);
        }
    }
}
