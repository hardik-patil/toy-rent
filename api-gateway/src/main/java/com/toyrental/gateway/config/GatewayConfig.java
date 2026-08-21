package com.toyrental.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

@Configuration
public class GatewayConfig {

    /**
     * Requests are rate-limited per client IP since routes are reachable pre-authentication.
     */
    @Bean
    public KeyResolver keyResolver() {
        return exchange -> Mono.justOrEmpty(exchange.getRequest().getRemoteAddress())
                .map(address -> address.getAddress().getHostAddress())
                .defaultIfEmpty("unknown");
    }

    @Bean
    public RouterFunction<ServerResponse> fallbackRoutes() {
        return RouterFunctions.route()
                .GET("/fallback/toy-service", request -> fallbackResponse(request.path(), "toy-service"))
                .GET("/fallback/booking-service", request -> fallbackResponse(request.path(), "booking-service"))
                .build();
    }

    private Mono<ServerResponse> fallbackResponse(String path, String service) {
        return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "timestamp", Instant.now().toString(),
                        "status", HttpStatus.SERVICE_UNAVAILABLE.value(),
                        "error", "SERVICE_UNAVAILABLE",
                        "message", service + " is temporarily unavailable, please try again shortly",
                        "path", path
                ));
    }

}
