package com.gkcontas.resilience.experiment;

import java.util.ArrayList;
import java.util.List;

public record Stats(int samples, double meanMillis, double p50Millis, double p95Millis, double maxMillis) {

    public static Stats ofNanos(List<Long> nanos) {
        if (nanos.isEmpty()) {
            return new Stats(0, 0, 0, 0, 0);
        }
        List<Long> sorted = new ArrayList<>(nanos);
        sorted.sort(Long::compareTo);
        double sum = 0;
        for (long value : sorted) {
            sum += value;
        }
        return new Stats(sorted.size(),
                round(sum / sorted.size()),
                round(sorted.get(percentileIndex(sorted.size(), 50))),
                round(sorted.get(percentileIndex(sorted.size(), 95))),
                round(sorted.get(sorted.size() - 1)));
    }

    private static int percentileIndex(int size, int percentile) {
        int index = (int) Math.ceil(size * percentile / 100.0) - 1;
        return Math.max(0, Math.min(size - 1, index));
    }

    /** Nanos to millis, kept to a tenth of a microsecond: a refused call is fast. */
    private static double round(double nanos) {
        return Math.round(nanos / 100.0) / 10_000.0;
    }
}
