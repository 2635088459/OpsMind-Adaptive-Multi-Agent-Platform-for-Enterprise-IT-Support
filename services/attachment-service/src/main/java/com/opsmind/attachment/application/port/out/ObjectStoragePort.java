package com.opsmind.attachment.application.port.out;

/**
 * 13-package-and-class-design-style boundary: this is the ONLY exit this service has
 * to real object storage (S3-compatible MinIO locally, per the frozen
 * technology-baseline) — no application service reaches a storage client directly.
 */
public interface ObjectStoragePort {

    void put(String objectKey, byte[] content, String contentType);

    byte[] get(String objectKey);
}
