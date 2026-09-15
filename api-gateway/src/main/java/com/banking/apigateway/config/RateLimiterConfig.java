package com.banking.apigateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.keyvalue.core.mapping.KeySpaceResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Configuration
public class RateLimiterConfig {
    @Bean
    public KeyResolver keyResolver() {
        return exchange -> {


            // this below code is just the code to extract the ip adrress used as a key in redis for rate limiter
            //rate limiter uses the given key from this function
            //application.yaml tells to use this key resolver for getting key
            //all rate limiting work is done by spring-cloud gateway itself written in application yaml
            // exchange = entire incoming HTTP request/response context
            // Example: GET /api/v1/account/123 from client 192.168.1.10

            return Mono.just(
                    exchange.getRequest()          // Get the incoming HTTP request
                            .getRemoteAddress()    // Get client's address: /192.168.1.10:54321
                            .getAddress()           // Get the actual IP address: 192.168.1.10
                            .getHostAddress()       // Convert IP address to String: "192.168.1.10"
            );
        };
    }

}
