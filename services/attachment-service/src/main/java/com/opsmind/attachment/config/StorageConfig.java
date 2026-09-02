package com.opsmind.attachment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

/**
 * MinIO is S3-compatible but needs 2 real, non-default AWS SDK client settings to
 * actually work against it (confirmed the hard way — the SDK's own defaults target
 * real AWS S3): path-style access (MinIO does not do virtual-hosted-style bucket-in-
 * hostname addressing the way real S3 does) and an explicit endpointOverride
 * (otherwise the SDK resolves against a real AWS region endpoint, never this
 * container). {@code StorageProperties}/{@code AttachmentProperties}/
 * {@code AttachmentCorsProperties} are all registered via
 * {@code @ConfigurationPropertiesScan} on the main application class, not repeated
 * {@code @EnableConfigurationProperties} declarations here.
 */
@Configuration
public class StorageConfig {

    @Bean
    public S3Client s3Client(StorageProperties properties) {
        return S3Client.builder()
            .endpointOverride(URI.create(properties.endpoint()))
            .region(Region.of(properties.region()))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build();
    }
}
