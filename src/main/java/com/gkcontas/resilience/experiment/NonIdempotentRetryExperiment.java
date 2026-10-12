package com.gkcontas.resilience.experiment;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.gkcontas.resilience.faults.FaultInjector;
import com.gkcontas.resilience.shipment.ShipmentService;

/**
 * A retry cannot tell "the request never arrived" from "the answer never came
 * back". When the carrier writes the shipment and then fails to answer, three
 * attempts mean three shipments - and on a payment API, three charges.
 *
 * <p>The number is read from the carrier's own request journal, not from the
 * application's point of view, which still sees a single failed call.
 */
@Component
public class NonIdempotentRetryExperiment implements Experiment {

    private final ShipmentService service;
    private final FaultInjector faults;

    public NonIdempotentRetryExperiment(ShipmentService service, FaultInjector faults) {
        this.service = service;
        this.faults = faults;
    }

    @Override
    public String name() {
        return "non-idempotent-retry";
    }

    @Override
    public Object run(Map<String, String> params) {
        faults.shipmentRecordedThenFails();

        String withRetryOutcome = call(true);
        int withRetry = faults.requestCount("POST", "/carrier/shipments");

        faults.resetJournal();
        String withoutRetryOutcome = call(false);
        int withoutRetry = faults.requestCount("POST", "/carrier/shipments");

        return new Result(1, withRetry, withRetryOutcome, withoutRetry, withoutRetryOutcome);
    }

    private String call(boolean retry) {
        try {
            if (retry) {
                service.registerWithRetry("SKU-SHIP", 2);
            } else {
                service.registerWithoutRetry("SKU-SHIP", 2);
            }
            return "answered";
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName();
        }
    }

    public record Result(int logicalCalls, int shipmentsRecordedWithRetry, String withRetryOutcome,
            int shipmentsRecordedWithoutRetry, String withoutRetryOutcome) {
    }
}
