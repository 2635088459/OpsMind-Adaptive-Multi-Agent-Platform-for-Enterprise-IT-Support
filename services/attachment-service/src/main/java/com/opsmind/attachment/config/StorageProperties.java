package com.opsmind.attachment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Frozen technology-baseline §"Object Storage": "S3-Compatible; local MinIO." Real credentials, never a committed placeholder that looks real. */
@ConfigurationProperties(prefix = "opsmind.attachment.storage")
public record StorageProperties(String endpoint, String accessKey, String secretKey, String region) {
}
