package com.opsmind.policygovernance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * domain 10's own support console (a separate browser origin) calls {@code
 * GovernanceAuditController}/{@code ApprovalController} directly (SPEC-SC-006/
 * 008/009) — real CORS is required or the browser blocks the response
 * outright. Every endpoint here is Bearer-token-authenticated, no cookie
 * ever crosses this boundary, so credentials stay disabled — same posture
 * as ticket-workflow-service's own {@code PortalCorsProperties}. Empty by
 * default (deny by default) — an operator opts specific frontend origins in.
 */
@ConfigurationProperties(prefix = "opsmind.portal.cors")
public record PortalCorsProperties(List<String> allowedOrigins) {

    public PortalCorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
