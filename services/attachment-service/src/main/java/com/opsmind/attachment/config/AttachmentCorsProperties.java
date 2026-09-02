package com.opsmind.attachment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Empty/deny by default — same convention every other Java service in this platform already uses (e.g. ticket-workflow-service's own PortalCorsProperties). */
@ConfigurationProperties(prefix = "opsmind.attachment.cors")
public record AttachmentCorsProperties(List<String> allowedOrigins) {

    public AttachmentCorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
