package com.opsmind.policygovernance.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI policyApprovalGovernanceOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("Policy Approval Governance Service")
                .description("Governance facts only: policy decisions and approval outcomes (INV-PG-001).")
                .version("v1"))
            .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
            .schemaRequirement(BEARER_SCHEME, new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT"));
    }
}
