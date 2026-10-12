package com.gkcontas.resilience.experiment;

import java.util.Map;

/**
 * Each experiment sets up a scenario on the fake carrier, measures it and
 * answers the numbers. They exist so the README can quote a run instead of an
 * estimate: the same endpoint a test calls is the one a reader can call.
 */
public interface Experiment {

    String name();

    Object run(Map<String, String> params);
}
