package com.opsmind.attachment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** SPEC-EP-011 §"Design References": "allowed MIME types / max size, sourced from the shared attachments capability's own contract" — this record IS that contract, now real. */
@ConfigurationProperties(prefix = "opsmind.attachment")
public record AttachmentProperties(List<String> allowedMimeTypes, long maxSizeBytes, String storageBucket) {

    public AttachmentProperties {
        allowedMimeTypes = allowedMimeTypes == null ? List.of() : List.copyOf(allowedMimeTypes);
    }
}
