package com.banking.paymentservice.config;

import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig {
    @Bean//this bean says put the returned object in container
    public WebMvcConfigurer corsConfigure(){
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings( CorsRegistry registry){
                registry.addMapping("/**")
                        .allowedHeaders("*")
                        .allowedMethods("GET","POST","PUT","DELETE");
            }
        };
    }
}
