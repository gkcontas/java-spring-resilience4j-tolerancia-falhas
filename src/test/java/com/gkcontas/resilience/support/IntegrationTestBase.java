package com.gkcontas.resilience.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.gkcontas.resilience.faults.FaultInjector;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * The fake carrier runs in a container, not in this JVM, so the failures the
 * policies are measured against are transport failures: a real read timeout, a
 * real connection reset.
 *
 * <p>Singleton container in a static initialiser, never {@code @Testcontainers}:
 * Spring caches contexts across test classes, and a per-class container is torn
 * down while a cached context still points at it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    protected static final GenericContainer<?> CARRIER =
            new GenericContainer<>(DockerImageName.parse("wiremock/wiremock:3.9.2"))
                    .withExposedPorts(8080)
                    .withCommand("--port", "8080", "--local-response-templating", "--disable-banner")
                    .waitingFor(Wait.forHttp("/__admin/mappings").forPort(8080).forStatusCode(200));

    static {
        CARRIER.start();
    }

    @DynamicPropertySource
    static void carrierProperties(DynamicPropertyRegistry registry) {
        registry.add("carrier.base-url",
                () -> "http://" + CARRIER.getHost() + ":" + CARRIER.getMappedPort(8080));
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected FaultInjector faults;

    @Autowired
    protected CircuitBreakerRegistry circuitBreakerRegistry;

    /**
     * The named instances live in the application context and are shared by
     * every test in it. Without this the order of the classes decides whether a
     * circuit starts closed.
     */
    @BeforeEach
    void resetPolicies() {
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(breaker -> breaker.reset());
        faults.healthy();
    }

    protected String selfBaseUrl() {
        return "http://localhost:" + port;
    }
}
