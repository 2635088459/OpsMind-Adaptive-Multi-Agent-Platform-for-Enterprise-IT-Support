package com.opsmind.attachment.infrastructure.storage;

import com.opsmind.attachment.application.exception.ObjectStorageUnavailableException;
import com.opsmind.attachment.application.port.out.ObjectStoragePort;
import com.opsmind.attachment.config.AttachmentProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;

/** The real adapter behind {@link ObjectStoragePort} — MinIO/S3, per the frozen technology-baseline. */
@Component
public class MinioObjectStorageAdapter implements ObjectStoragePort {

    private final S3Client s3Client;
    private final AttachmentProperties properties;

    public MinioObjectStorageAdapter(S3Client s3Client, AttachmentProperties properties) {
        this.s3Client = s3Client;
        this.properties = properties;
    }

    @Override
    public void put(String objectKey, byte[] content, String contentType) {
        try {
            s3Client.putObject(
                PutObjectRequest.builder().bucket(properties.storageBucket()).key(objectKey).contentType(contentType).build(),
                RequestBody.fromBytes(content)
            );
        } catch (SdkException e) {
            throw new ObjectStorageUnavailableException("failed to store object " + objectKey, e);
        }
    }

    @Override
    public byte[] get(String objectKey) {
        try (ResponseInputStream<GetObjectResponse> response = s3Client.getObject(
            GetObjectRequest.builder().bucket(properties.storageBucket()).key(objectKey).build()
        )) {
            return response.readAllBytes();
        } catch (SdkException | IOException e) {
            throw new ObjectStorageUnavailableException("failed to retrieve object " + objectKey, e);
        }
    }
}
