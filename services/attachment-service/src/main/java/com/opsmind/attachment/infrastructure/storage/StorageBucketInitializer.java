package com.opsmind.attachment.infrastructure.storage;

import com.opsmind.attachment.config.AttachmentProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** A fresh MinIO instance has no bucket yet — created once at startup, idempotently (a bucket that already exists is a no-op, not an error). */
@Component
public class StorageBucketInitializer {

    private static final Logger log = LoggerFactory.getLogger(StorageBucketInitializer.class);

    private final S3Client s3Client;
    private final AttachmentProperties properties;

    public StorageBucketInitializer(S3Client s3Client, AttachmentProperties properties) {
        this.s3Client = s3Client;
        this.properties = properties;
    }

    @PostConstruct
    public void ensureBucketExists() {
        String bucket = properties.storageBucket();
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            log.info("attachment storage bucket {} already exists", bucket);
        } catch (NoSuchBucketException e) {
            createBucket(bucket);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                createBucket(bucket);
            } else {
                throw e;
            }
        }
    }

    private void createBucket(String bucket) {
        s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        log.info("created attachment storage bucket {}", bucket);
    }
}
