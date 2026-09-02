package com.opsmind.attachment.integration;

import com.opsmind.attachment.application.exception.AttachmentNotFoundException;
import com.opsmind.attachment.application.port.in.FetchAttachmentContentUseCase;
import com.opsmind.attachment.application.port.in.UploadAttachmentCommand;
import com.opsmind.attachment.application.port.in.UploadAttachmentUseCase;
import com.opsmind.attachment.application.port.in.FindAttachmentUseCase;
import com.opsmind.attachment.application.port.in.RequesterContext;
import com.opsmind.attachment.domain.Attachment;
import com.opsmind.attachment.support.MinioContainerSupport;
import com.opsmind.attachment.support.PostgresContainerSupport;
import com.opsmind.attachment.support.TestSecurityConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the real, wired application against a real Testcontainers Postgres (the
 * actual Flyway migration) AND a real Testcontainers MinIO (the actual S3 API) — not
 * the in-memory fakes AttachmentServiceTest uses. Proves this "shared attachments
 * capability" is genuinely real end-to-end, mirroring
 * policy-approval-governance-service's own GovernancePersistenceIT precedent for the
 * Postgres half, extended here with a real object-storage half no other Java
 * service in this platform has needed before.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@Testcontainers
class AttachmentPersistenceIT implements PostgresContainerSupport, MinioContainerSupport {

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("opsmind.attachment.storage.endpoint", () -> MINIO.getS3URL());
        registry.add("opsmind.attachment.storage.access-key", MINIO::getUserName);
        registry.add("opsmind.attachment.storage.secret-key", MINIO::getPassword);
        registry.add("opsmind.attachment.storage.region", () -> "us-east-1");
    }

    @Autowired
    private UploadAttachmentUseCase uploadAttachmentUseCase;

    @Autowired
    private FindAttachmentUseCase findAttachmentUseCase;

    @Autowired
    private FetchAttachmentContentUseCase fetchAttachmentContentUseCase;

    @Test
    void uploadingPersistsRealMetadataInPostgresAndRealBytesInMinio() {
        byte[] content = {1, 2, 3, 4, 5};

        RequesterContext uploader = new RequesterContext("employee-1", "EMPLOYEE");
        Attachment uploaded = uploadAttachmentUseCase.upload(new UploadAttachmentCommand("photo.png", "image/png", content, "employee-1"));

        Attachment reloaded = findAttachmentUseCase.findById(uploaded.attachmentId(), uploader);
        assertThat(reloaded.filename()).isEqualTo("photo.png");
        assertThat(reloaded.sizeBytes()).isEqualTo(5);

        FetchAttachmentContentUseCase.AttachmentContent fetched = fetchAttachmentContentUseCase.fetchContent(uploaded.attachmentId(), uploader);
        assertThat(fetched.content()).containsExactly(1, 2, 3, 4, 5);
        assertThat(fetched.mimeType()).isEqualTo("image/png");
    }

    @Test
    void findingAnAttachmentThatWasNeverUploadedThrowsAgainstTheRealDatabase() {
        RequesterContext requester = new RequesterContext("employee-1", "EMPLOYEE");
        assertThatThrownBy(() -> findAttachmentUseCase.findById(UUID.randomUUID(), requester)).isInstanceOf(AttachmentNotFoundException.class);
    }
}
