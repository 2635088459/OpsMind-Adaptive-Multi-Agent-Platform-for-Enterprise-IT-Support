package com.opsmind.attachment.support;

import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;

/** A real MinIO container — the same real S3 API surface production points at (frozen technology-baseline §"Object Storage"), not a hand-rolled fake object-storage adapter. */
public interface MinioContainerSupport {

    @Container
    MinIOContainer MINIO = new MinIOContainer("minio/minio:RELEASE.2024-11-07T00-52-20Z");
}
