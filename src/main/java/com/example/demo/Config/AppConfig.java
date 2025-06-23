package com.example.demo.Config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class AppConfig {
    @Bean // This annotation tells Spring to create a bean for RestTemplate
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
