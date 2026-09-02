package com.opsmind.attachment.application;

import com.opsmind.attachment.application.exception.AttachmentAccessDeniedException;
import com.opsmind.attachment.application.exception.AttachmentNotFoundException;
import com.opsmind.attachment.application.exception.AttachmentTooLargeException;
import com.opsmind.attachment.application.exception.UnsupportedMimeTypeException;
import com.opsmind.attachment.application.port.in.FetchAttachmentContentUseCase;
import com.opsmind.attachment.application.port.in.RequesterContext;
import com.opsmind.attachment.application.port.in.UploadAttachmentCommand;
import com.opsmind.attachment.application.port.out.AttachmentRepository;
import com.opsmind.attachment.application.port.out.ObjectStoragePort;
import com.opsmind.attachment.config.AttachmentProperties;
import com.opsmind.attachment.domain.Attachment;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real fakes, not Mockito — matches this codebase's own established preference for hermetic hand-rolled test doubles over the ticket-workflow-service/policy-approval-governance-service sibling services' own precedent where feasible. */
class AttachmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");
    private static final RequesterContext UPLOADER = new RequesterContext("employee-1", "EMPLOYEE");

    private final Map<UUID, Attachment> storedAttachments = new HashMap<>();
    private final Map<String, byte[]> storedObjects = new HashMap<>();

    private final AttachmentRepository repository = new AttachmentRepository() {
        @Override
        public Attachment save(Attachment attachment) {
            storedAttachments.put(attachment.attachmentId(), attachment);
            return attachment;
        }

        @Override
        public Optional<Attachment> findById(UUID attachmentId) {
            return Optional.ofNullable(storedAttachments.get(attachmentId));
        }
    };

    private final ObjectStoragePort objectStoragePort = new ObjectStoragePort() {
        @Override
        public void put(String objectKey, byte[] content, String contentType) {
            storedObjects.put(objectKey, content);
        }

        @Override
        public byte[] get(String objectKey) {
            return storedObjects.get(objectKey);
        }
    };

    private final AttachmentProperties properties = new AttachmentProperties(List.of("image/png", "image/jpeg"), 1024, "opsmind-attachments-test");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AttachmentService service = new AttachmentService(repository, objectStoragePort, properties, clock);

    @Test
    void uploadingAnAllowedFileStoresItAndPersistsReadyMetadata() {
        Attachment attachment = service.upload(new UploadAttachmentCommand("screenshot.png", "image/png", new byte[]{1, 2, 3}, "employee-1"));

        assertThat(attachment.filename()).isEqualTo("screenshot.png");
        assertThat(attachment.mimeType()).isEqualTo("image/png");
        assertThat(attachment.sizeBytes()).isEqualTo(3);
        assertThat(attachment.status().name()).isEqualTo("READY");
        assertThat(attachment.uploadedBy()).isEqualTo("employee-1");
        assertThat(attachment.createdAt()).isEqualTo(NOW);
        assertThat(storedObjects).containsKey(attachment.objectKey());
    }

    @Test
    void uploadingAnUnsupportedMimeTypeIsRejectedBeforeAnyStorageCall() {
        assertThatThrownBy(() -> service.upload(new UploadAttachmentCommand("payload.exe", "application/x-msdownload", new byte[]{1}, "employee-1")))
            .isInstanceOf(UnsupportedMimeTypeException.class);
        assertThat(storedObjects).isEmpty();
        assertThat(storedAttachments).isEmpty();
    }

    @Test
    void uploadingAFileOverTheRealServerSideLimitIsRejected() {
        byte[] tooLarge = new byte[2048];
        assertThatThrownBy(() -> service.upload(new UploadAttachmentCommand("big.png", "image/png", tooLarge, "employee-1")))
            .isInstanceOf(AttachmentTooLargeException.class);
        assertThat(storedObjects).isEmpty();
    }

    @Test
    void aMaliciousFilenameNeverInfluencesTheRealObjectKeyPath() {
        Attachment attachment = service.upload(new UploadAttachmentCommand("../../etc/passwd.png", "image/png", new byte[]{1}, "employee-1"));

        assertThat(attachment.objectKey()).doesNotContain("..").doesNotContain("/etc/");
    }

    @Test
    void findingAnUnknownAttachmentThrows() {
        assertThatThrownBy(() -> service.findById(UUID.randomUUID(), UPLOADER)).isInstanceOf(AttachmentNotFoundException.class);
    }

    @Test
    void fetchingContentReturnsTheRealStoredBytesAndMetadata() {
        Attachment attachment = service.upload(new UploadAttachmentCommand("note.png", "image/png", new byte[]{9, 8, 7}, "employee-1"));

        FetchAttachmentContentUseCase.AttachmentContent content = service.fetchContent(attachment.attachmentId(), UPLOADER);

        assertThat(content.content()).containsExactly(9, 8, 7);
        assertThat(content.mimeType()).isEqualTo("image/png");
        assertThat(content.filename()).isEqualTo("note.png");
    }

    @Test
    void theUploaderCanReadTheirOwnAttachment() {
        Attachment attachment = service.upload(new UploadAttachmentCommand("note.png", "image/png", new byte[]{1}, "employee-1"));

        Attachment found = service.findById(attachment.attachmentId(), UPLOADER);

        assertThat(found.attachmentId()).isEqualTo(attachment.attachmentId());
    }

    @Test
    void aNonEmployeeServiceIdentityCanReadAnyAttachment() {
        Attachment attachment = service.upload(new UploadAttachmentCommand("note.png", "image/png", new byte[]{1}, "employee-1"));
        RequesterContext serviceIdentity = new RequesterContext("agent-runtime-service", "SUPPORT_AGENT");

        Attachment found = service.findById(attachment.attachmentId(), serviceIdentity);

        assertThat(found.attachmentId()).isEqualTo(attachment.attachmentId());
    }

    @Test
    void aDifferentEmployeeCannotReadSomeoneElsesAttachment() {
        Attachment attachment = service.upload(new UploadAttachmentCommand("note.png", "image/png", new byte[]{1}, "employee-1"));
        RequesterContext otherEmployee = new RequesterContext("employee-2", "EMPLOYEE");

        assertThatThrownBy(() -> service.findById(attachment.attachmentId(), otherEmployee))
            .isInstanceOf(AttachmentAccessDeniedException.class);
    }

    @Test
    void aDifferentEmployeeCannotFetchSomeoneElsesAttachmentContentEither() {
        Attachment attachment = service.upload(new UploadAttachmentCommand("note.png", "image/png", new byte[]{1}, "employee-1"));
        RequesterContext otherEmployee = new RequesterContext("employee-2", "EMPLOYEE");

        assertThatThrownBy(() -> service.fetchContent(attachment.attachmentId(), otherEmployee))
            .isInstanceOf(AttachmentAccessDeniedException.class);
    }
}
