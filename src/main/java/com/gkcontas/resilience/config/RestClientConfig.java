package com.gkcontas.resilience.config;

import java.net.http.HttpClient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(CarrierProperties.class)
public class RestClientConfig {

    /**
     * The client used by the application to call the carrier. Both timeouts are
     * explicit: a client with no read timeout waits forever, and then no
     * circuit breaker ever sees a failure to record - it sees a thread that
     * never comes back.
     */
    @Bean
    RestClient carrierRestClient(CarrierProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory(properties))
                .build();
    }

    /**
     * A separate client for the admin API of the fake carrier. It must not
     * share the timeouts of the hot path: programming a stub is allowed to take
     * longer than the read timeout the experiments are measuring.
     */
    @Bean
    RestClient carrierAdminRestClient(CarrierProperties properties) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(java.time.Duration.ofSeconds(5))
                        .build());
        factory.setReadTimeout(java.time.Duration.ofSeconds(30));
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * HTTP/1.1 on purpose. The JDK client negotiates HTTP/2 when the server
     * offers it, and then every request to the carrier shares one multiplexed
     * connection: a read timeout cancels its stream, and the sibling requests
     * in flight on that same connection die with "Received RST_STREAM: Stream
     * cancelled". A timeout policy is only measurable if a timeout costs one
     * call and not the ones next to it.
     */
    private static JdkClientHttpRequestFactory requestFactory(CarrierProperties properties) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(properties.connectTimeout())
                        .build());
        factory.setReadTimeout(properties.readTimeout());
        return factory;
    }
}
