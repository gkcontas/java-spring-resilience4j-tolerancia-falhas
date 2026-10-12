package com.gkcontas.resilience.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExecutorConfig {

    /**
     * Platform threads, bounded on purpose. The point of the measurements is
     * what happens when a slow dependency eats a finite resource, and a virtual
     * thread pool would hide exactly that - the subject of project 25.
     */
    @Bean(destroyMethod = "shutdownNow")
    ExecutorService quoteExecutor() {
        return Executors.newFixedThreadPool(8, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("quote-" + thread.threadId());
            thread.setDaemon(true);
            return thread;
        });
    }
}
