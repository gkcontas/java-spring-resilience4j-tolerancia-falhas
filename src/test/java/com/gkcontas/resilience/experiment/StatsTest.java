package com.gkcontas.resilience.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StatsTest {

    @Test
    @DisplayName("an empty sample is zero on every field instead of blowing up mid measurement")
    void emptySample() {
        Stats stats = Stats.ofNanos(List.of());

        assertThat(stats.samples()).isZero();
        assertThat(stats.p50Millis()).isZero();
        assertThat(stats.maxMillis()).isZero();
    }

    @Test
    @DisplayName("the median is the middle value, in milliseconds")
    void median() {
        Stats stats = Stats.ofNanos(List.of(1_000_000L, 2_000_000L, 3_000_000L));

        assertThat(stats.samples()).isEqualTo(3);
        assertThat(stats.p50Millis()).isEqualTo(2.0);
        assertThat(stats.maxMillis()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("p95 of a hundred samples is the ninety fifth, not the last")
    void p95() {
        List<Long> samples = new java.util.ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            samples.add(i * 1_000_000L);
        }

        Stats stats = Stats.ofNanos(samples);

        assertThat(stats.p95Millis()).isEqualTo(95.0);
        assertThat(stats.maxMillis()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("the input order does not change the percentiles")
    void unsortedInput() {
        Stats stats = Stats.ofNanos(List.of(5_000_000L, 1_000_000L, 3_000_000L, 2_000_000L, 4_000_000L));

        assertThat(stats.p50Millis()).isEqualTo(3.0);
        assertThat(stats.meanMillis()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("microsecond scale survives the rounding")
    void microsecondScale() {
        Stats stats = Stats.ofNanos(List.of(1_500L, 2_500L, 3_500L));

        assertThat(stats.p50Millis()).isEqualTo(0.0025);
    }
}
