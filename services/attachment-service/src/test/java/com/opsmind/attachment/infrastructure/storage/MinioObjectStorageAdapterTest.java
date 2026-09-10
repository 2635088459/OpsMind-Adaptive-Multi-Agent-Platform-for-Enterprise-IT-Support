package com.opsmind.attachment.infrastructure.storage;

import com.opsmind.attachment.application.exception.ObjectStorageUnavailableException;
import com.opsmind.attachment.config.AttachmentProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the one bit of real logic {@link MinioObjectStorageAdapter} owns
 * beyond delegating to the AWS SDK: it pins bucket/key/content-type onto the request
 * and wraps every {@code SdkException} as {@link ObjectStorageUnavailableException} so
 * the application layer never has to know about the S3 SDK's own exception tree.
 * The real round-trip against a live MinIO is {@code AttachmentPersistenceIT}'s job.
 */
class MinioObjectStorageAdapterTest {

    private final S3Client s3 = mock(S3Client.class);
    private final AttachmentProperties properties =
        new AttachmentProperties(List.of("image/png"), 26_214_400L, "opsmind-attachments");
    private final MinioObjectStorageAdapter adapter = new MinioObjectStorageAdapter(s3, properties);

    @Test
    void put_targets_the_configured_bucket_with_the_given_key_and_content_type() {
        adapter.put("attachments/abc", "hello".getBytes(StandardCharsets.UTF_8), "image/png");

        ArgumentCaptor<PutObjectRequest> req = ArgumentCaptor.forClass(PutObjectRequest.class);
        org.mockito.Mockito.verify(s3).putObject(req.capture(), any(RequestBody.class));
        assertThat(req.getValue().bucket()).isEqualTo("opsmind-attachments");
        assertThat(req.getValue().key()).isEqualTo("attachments/abc");
        assertThat(req.getValue().contentType()).isEqualTo("image/png");
    }

    @Test
    void put_wraps_an_sdk_failure_as_ObjectStorageUnavailableException() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
            .thenThrow(SdkClientException.create("connection refused"));

        assertThatThrownBy(() -> adapter.put("k", new byte[]{1}, "image/png"))
            .isInstanceOf(ObjectStorageUnavailableException.class)
            .hasMessageContaining("k");
    }

    @Test
    void get_returns_the_stored_bytes() {
        byte[] stored = "the real bytes".getBytes(StandardCharsets.UTF_8);
        ResponseInputStream<GetObjectResponse> response = new ResponseInputStream<>(
            GetObjectResponse.builder().build(),
            AbortableInputStream.create(new ByteArrayInputStream(stored))
        );
        when(s3.getObject(any(GetObjectRequest.class))).thenReturn(response);

        assertThat(adapter.get("attachments/abc")).isEqualTo(stored);
    }

    @Test
    void get_wraps_an_sdk_failure_as_ObjectStorageUnavailableException() {
        when(s3.getObject(any(GetObjectRequest.class))).thenThrow(SdkClientException.create("no such key"));

        assertThatThrownBy(() -> adapter.get("missing"))
            .isInstanceOf(ObjectStorageUnavailableException.class)
            .hasMessageContaining("missing");
    }
}
