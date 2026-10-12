package com.gkcontas.resilience.shipment;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gkcontas.resilience.carrier.ShipmentReceipt;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/shipments")
public class ShipmentController {

    private final ShipmentService service;

    public ShipmentController(ShipmentService service) {
        this.service = service;
    }

    @PostMapping
    public ShipmentReceipt register(@Valid @RequestBody ShipmentRequest request,
            @RequestParam(defaultValue = "true") boolean retry) {
        return retry
                ? service.registerWithRetry(request.sku(), request.quantity())
                : service.registerWithoutRetry(request.sku(), request.quantity());
    }
}
