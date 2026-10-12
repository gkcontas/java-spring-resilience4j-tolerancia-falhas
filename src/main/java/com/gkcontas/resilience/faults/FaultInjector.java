package com.gkcontas.resilience.faults;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Programs the fake carrier through the WireMock admin API.
 *
 * <p>The faults live on the other side of a real socket on purpose. A read
 * timeout, a connection reset and a 500 reach the client as three different
 * things, and a stub inside this JVM cannot tell them apart - which is exactly
 * what every policy below depends on.
 */
@Component
public class FaultInjector {

    private static final String QUOTE_PATH = "/carrier/quotes/([^/]+)";
    private static final String QUOTE_BODY = """
            {"sku":"{{request.pathSegments.[2]}}","carrier":"SwiftShip",\
            "amountCents":4990,"etaDays":3}""";
    // The templated field must not be the last one: a Handlebars expression
    // followed immediately by the closing brace of the JSON object reads as
    // '}}}' and the template fails to parse, which WireMock answers with a 500.
    private static final String RECEIPT_BODY = """
            {"receiptId":"{{randomValue type='UUID'}}",\
            "quantity":{{jsonPath request.body '$.quantity'}},\
            "sku":"{{jsonPath request.body '$.sku'}}"}""";

    private final RestClient admin;

    public FaultInjector(RestClient carrierAdminRestClient) {
        this.admin = carrierAdminRestClient;
    }

    /** Healthy carrier, empty request journal. */
    public void healthy() {
        resetAll();
        stub(quoteRequest(), okQuote(0));
        stub(shipmentRequest(), okReceipt());
    }

    /** Answers 200, but only after the given delay. */
    public void slow(Duration delay) {
        resetAll();
        stub(quoteRequest(), okQuote(delay.toMillis()));
        stub(shipmentRequest(), okReceipt());
    }

    /** Answers 500 to every quote. */
    public void serverError() {
        resetAll();
        stub(quoteRequest(), Map.of("status", 500, "body", "carrier is on fire"));
        stub(shipmentRequest(), okReceipt());
    }

    /** Drops the connection mid flight: no status code ever arrives. */
    public void connectionReset() {
        resetAll();
        stub(quoteRequest(), Map.of("fault", "CONNECTION_RESET_BY_PEER"));
        stub(shipmentRequest(), okReceipt());
    }

    /** Answers 404: a working service saying this SKU has no quote. */
    public void notFound() {
        resetAll();
        stub(quoteRequest(), Map.of("status", 404, "body", "unknown sku"));
        stub(shipmentRequest(), okReceipt());
    }

    /** Fails the first N quote calls and answers normally from then on. */
    public void failThenRecover(int failures) {
        resetAll();
        for (int i = 0; i < failures; i++) {
            Map<String, Object> request = new java.util.HashMap<>(quoteRequest());
            stub(request, Map.of("status", 500, "body", "carrier is on fire"),
                    scenario(i == 0 ? "Started" : "attempt-" + i, "attempt-" + (i + 1)));
        }
        stub(quoteRequest(), okQuote(0), scenario("attempt-" + failures, "attempt-" + failures));
        stub(shipmentRequest(), okReceipt());
    }

    /**
     * The shipment is recorded and THEN the answer fails. The request journal
     * still counts the call, which is how the duplication a retry causes
     * becomes a number instead of a worry.
     */
    public void shipmentRecordedThenFails() {
        resetAll();
        stub(quoteRequest(), okQuote(0));
        stub(shipmentRequest(), Map.of("status", 500, "body", "receipt written, answer lost"));
    }

    /**
     * Fails one call out of every {@code outOf}: a service that is mostly back,
     * which is what tells apart a half-open state that probes once from one
     * that probes several times and decides on a rate.
     */
    public void failsOneIn(int outOf) {
        resetAll();
        stub(quoteRequest(), Map.of("status", 500, "body", "carrier is on fire"),
                scenario("Started", "ok-1"));
        for (int i = 1; i < outOf; i++) {
            stub(quoteRequest(), okQuote(0),
                    scenario("ok-" + i, i + 1 < outOf ? "ok-" + (i + 1) : "Started"));
        }
        stub(shipmentRequest(), okReceipt());
    }

    public int requestCount(String method, String urlPathPattern) {
        Map<?, ?> body = admin.post()
                .uri("/__admin/requests/count")
                .body(Map.of("method", method, "urlPathPattern", urlPathPattern))
                .retrieve()
                .body(Map.class);
        return body == null ? 0 : ((Number) body.get("count")).intValue();
    }

    /** Epoch millis of every matching request the carrier received, sorted. */
    @SuppressWarnings("unchecked")
    public List<Long> requestTimestamps(String method, String urlPathPattern) {
        Map<?, ?> body = admin.post()
                .uri("/__admin/requests/find")
                .body(Map.of("method", method, "urlPathPattern", urlPathPattern))
                .retrieve()
                .body(Map.class);
        List<Long> timestamps = new ArrayList<>();
        if (body == null) {
            return timestamps;
        }
        for (Map<String, Object> request : (List<Map<String, Object>>) body.get("requests")) {
            timestamps.add(((Number) request.get("loggedDate")).longValue());
        }
        timestamps.sort(Long::compareTo);
        return timestamps;
    }

    public void resetJournal() {
        admin.delete().uri("/__admin/requests").retrieve().toBodilessEntity();
    }

    private void resetAll() {
        admin.post().uri("/__admin/reset").retrieve().toBodilessEntity();
    }

    private void stub(Map<String, Object> request, Map<String, Object> response) {
        stub(request, response, Map.of());
    }

    private void stub(Map<String, Object> request, Map<String, Object> response, Map<String, Object> extra) {
        Map<String, Object> mapping = new java.util.HashMap<>();
        mapping.put("request", request);
        mapping.put("response", response);
        mapping.putAll(extra);
        admin.post().uri("/__admin/mappings").body(mapping).retrieve().toBodilessEntity();
    }

    private static Map<String, Object> scenario(String required, String next) {
        return Map.of("scenarioName", "carrier", "requiredScenarioState", required, "newScenarioState", next);
    }

    private static Map<String, Object> quoteRequest() {
        return Map.of("method", "GET", "urlPathPattern", QUOTE_PATH);
    }

    private static Map<String, Object> shipmentRequest() {
        return Map.of("method", "POST", "urlPath", "/carrier/shipments");
    }

    private static Map<String, Object> okQuote(long delayMillis) {
        Map<String, Object> response = new java.util.HashMap<>(Map.of(
                "status", 200,
                "headers", Map.of("Content-Type", "application/json"),
                "body", QUOTE_BODY,
                "transformers", List.of("response-template")));
        if (delayMillis > 0) {
            response.put("fixedDelayMilliseconds", delayMillis);
        }
        return response;
    }

    private static Map<String, Object> okReceipt() {
        return Map.of(
                "status", 200,
                "headers", Map.of("Content-Type", "application/json"),
                "body", RECEIPT_BODY,
                "transformers", List.of("response-template"));
    }
}
