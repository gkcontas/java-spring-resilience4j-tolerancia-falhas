package com.gkcontas.resilience.catalog;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The healthy neighbour. It depends on nothing outside this JVM, so any latency
 * measured here comes from contention for the container's threads - which is
 * exactly what the bulkhead experiment is about.
 */
@RestController
@RequestMapping("/catalog")
public class CatalogController {

    @GetMapping("/{sku}")
    public CatalogItem item(@PathVariable String sku) {
        return new CatalogItem(sku, "item " + sku, 1990L + sku.hashCode() % 100);
    }
}
