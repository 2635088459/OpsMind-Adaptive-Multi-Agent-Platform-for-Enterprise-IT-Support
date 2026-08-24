package com.opsmind.identity.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI userAccessAuthenticationOpenApi() {
        return new OpenAPI().info(new Info()
            .title("User Access And Authentication Service")
            .description("Domain 01 identity and authorization security boundary API.")
            .version("v1"));
    }
}
