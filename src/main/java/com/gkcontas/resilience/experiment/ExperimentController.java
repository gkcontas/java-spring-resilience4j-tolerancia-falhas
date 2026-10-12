package com.gkcontas.resilience.experiment;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/experiments")
public class ExperimentController {

    private final Map<String, Experiment> experiments;

    public ExperimentController(List<Experiment> experiments) {
        this.experiments = experiments.stream()
                .collect(java.util.stream.Collectors.toMap(Experiment::name, e -> e));
    }

    @PostMapping("/{name}")
    public Object run(@PathVariable String name, @RequestParam Map<String, String> params) {
        Experiment experiment = experiments.get(name);
        if (experiment == null) {
            throw new IllegalArgumentException("unknown experiment: " + name + ", known: " + experiments.keySet());
        }
        return experiment.run(params);
    }

    @PostMapping
    public Map<String, Object> runAll(@RequestParam Map<String, String> params) {
        java.util.LinkedHashMap<String, Object> results = new java.util.LinkedHashMap<>();
        experiments.keySet().stream().sorted().forEach(name -> results.put(name, experiments.get(name).run(params)));
        return results;
    }
}
