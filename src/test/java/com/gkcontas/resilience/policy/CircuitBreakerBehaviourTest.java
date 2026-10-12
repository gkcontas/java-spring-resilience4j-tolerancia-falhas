package com.gkcontas.resilience.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gkcontas.resilience.carrier.CarrierUnavailableException;
import com.gkcontas.resilience.carrier.QuoteNotFoundException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

/**
 * Pure configuration, no infrastructure. Everything here is a claim about what
 * a given set of properties does, which is where most of the surprises in
 * production come from.
 */
class CircuitBreakerBehaviourTest {

    private static CircuitBreakerConfig.Builder base() {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .recordExceptions(CarrierUnavailableException.class);
    }

    private static Supplier<String> alwaysFailing() {
        return () -> {
            throw new CarrierUnavailableException("down");
        };
    }

    @Test
    @DisplayName("opens once the window has the minimum and the failure rate is above the threshold")
    void opensAtThreshold() {
        CircuitBreaker breaker = CircuitBreaker.of("open", base().build());

        for (int i = 0; i < 10; i++) {
            call(breaker, alwaysFailing());
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("stays closed below minimumNumberOfCalls, however bad the rate is")
    void minimumNumberOfCallsWins() {
        CircuitBreaker breaker = CircuitBreaker.of("minimum", base().minimumNumberOfCalls(100)
                .slidingWindowSize(100).build());

        for (int i = 0; i < 30; i++) {
            call(breaker, alwaysFailing());
        }

        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(30);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("an exception outside recordExceptions is not a failure, it is a success")
    void ignoredExceptionCountsAsSuccess() {
        CircuitBreaker breaker = CircuitBreaker.of("ignored", base().build());

        for (int i = 0; i < 20; i++) {
            call(breaker, () -> {
                throw new QuoteNotFoundException("SKU-1");
            });
        }

        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(breaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(10);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("with no recordExceptions list every exception is a failure")
    void defaultRecordsEverything() {
        CircuitBreaker breaker = CircuitBreaker.of("default-records", CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .build());

        for (int i = 0; i < 10; i++) {
            call(breaker, () -> {
                throw new QuoteNotFoundException("SKU-1");
            });
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("a call that only answers slowly is recorded as a slow call, not as a failure")
    void slowCallIsNotAFailure() {
        CircuitBreaker breaker = CircuitBreaker.of("slow", base()
                .slowCallDurationThreshold(Duration.ofMillis(20))
                .slowCallRateThreshold(50)
                .build());

        for (int i = 0; i < 10; i++) {
            call(breaker, () -> {
                sleep(40);
                return "ok";
            });
        }

        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(breaker.getMetrics().getNumberOfSlowCalls()).isEqualTo(10);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("without slow call detection the same calls leave the circuit closed")
    void slowCallsIgnoredByDefaultThresholds() {
        CircuitBreaker breaker = CircuitBreaker.of("slow-ignored", base().build());

        for (int i = 0; i < 10; i++) {
            call(breaker, () -> {
                sleep(40);
                return "ok";
            });
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("an open circuit refuses without calling the supplier")
    void openCircuitDoesNotCall() {
        CircuitBreaker breaker = CircuitBreaker.of("refuse", base().build());
        AtomicInteger calls = new AtomicInteger();
        breaker.transitionToOpenState();

        assertThatThrownBy(() -> breaker.executeSupplier(calls::incrementAndGet))
                .isInstanceOf(CallNotPermittedException.class);
        assertThat(calls.get()).isZero();
    }

    @Test
    @DisplayName("half-open admits exactly the permitted probes and refuses the rest")
    void halfOpenPermitsOnlyTheProbes() {
        CircuitBreaker breaker = CircuitBreaker.of("half-open", base()
                .permittedNumberOfCallsInHalfOpenState(3)
                .build());
        breaker.transitionToOpenState();
        breaker.transitionToHalfOpenState();

        AtomicInteger admitted = new AtomicInteger();
        int refused = 0;
        for (int i = 0; i < 10; i++) {
            try {
                breaker.executeSupplier(() -> {
                    admitted.incrementAndGet();
                    throw new CarrierUnavailableException("still down");
                });
            } catch (CallNotPermittedException e) {
                refused++;
            } catch (CarrierUnavailableException e) {
                // the probe failing is the point
            }
        }

        assertThat(admitted.get()).isEqualTo(3);
        assertThat(refused).isEqualTo(7);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("one bad probe with a single permitted call sends the circuit straight back to open")
    void onePermittedCallDecidesAlone() {
        CircuitBreaker breaker = CircuitBreaker.of("single-probe", base()
                .permittedNumberOfCallsInHalfOpenState(1)
                .build());
        breaker.transitionToOpenState();
        breaker.transitionToHalfOpenState();

        call(breaker, alwaysFailing());

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("three probes turn the decision into a rate: one failure out of three still closes")
    void threeProbesTolerateOneFailure() {
        CircuitBreaker breaker = CircuitBreaker.of("three-probes", base()
                .permittedNumberOfCallsInHalfOpenState(3)
                .build());
        breaker.transitionToOpenState();
        breaker.transitionToHalfOpenState();

        call(breaker, alwaysFailing());
        call(breaker, () -> "ok");
        call(breaker, () -> "ok");

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("reset clears the window as well as the state")
    void resetClearsTheWindow() {
        CircuitBreaker breaker = CircuitBreaker.of("reset", base().build());
        for (int i = 0; i < 10; i++) {
            call(breaker, alwaysFailing());
        }

        breaker.reset();

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    private static void call(CircuitBreaker breaker, Supplier<String> supplier) {
        try {
            breaker.executeSupplier(supplier);
        } catch (RuntimeException e) {
            // the state is what is being asserted
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
