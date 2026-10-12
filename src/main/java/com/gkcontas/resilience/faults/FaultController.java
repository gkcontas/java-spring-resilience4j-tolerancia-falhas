package com.gkcontas.resilience.faults;

import java.time.Duration;
import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets the carrier be broken on demand from outside the JVM, so the same
 * scenario can be reproduced by hand with curl and not only by a test.
 */
@RestController
@RequestMapping("/faults")
public class FaultController {

    private final FaultInjector faults;

    public FaultController(FaultInjector faults) {
        this.faults = faults;
    }

    @PostMapping("/healthy")
    public Map<String, String> healthy() {
        faults.healthy();
        return Map.of("carrier", "healthy");
    }

    @PostMapping("/slow")
    public Map<String, String> slow(@RequestParam(defaultValue = "3000") long millis) {
        faults.slow(Duration.ofMillis(millis));
        return Map.of("carrier", "slow", "delayMillis", String.valueOf(millis));
    }

    @PostMapping("/server-error")
    public Map<String, String> serverError() {
        faults.serverError();
        return Map.of("carrier", "server-error");
    }

    @PostMapping("/connection-reset")
    public Map<String, String> connectionReset() {
        faults.connectionReset();
        return Map.of("carrier", "connection-reset");
    }

    @PostMapping("/not-found")
    public Map<String, String> notFound() {
        faults.notFound();
        return Map.of("carrier", "not-found");
    }

    @PostMapping("/fail-then-recover")
    public Map<String, String> failThenRecover(@RequestParam(defaultValue = "2") int failures) {
        faults.failThenRecover(failures);
        return Map.of("carrier", "fail-then-recover", "failures", String.valueOf(failures));
    }
}
