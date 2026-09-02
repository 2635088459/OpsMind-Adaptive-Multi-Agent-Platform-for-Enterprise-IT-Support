package com.opsmind.attachment.application;

import com.opsmind.attachment.application.exception.AttachmentAccessDeniedException;
import com.opsmind.attachment.application.exception.AttachmentNotFoundException;
import com.opsmind.attachment.application.exception.AttachmentTooLargeException;
import com.opsmind.attachment.application.exception.UnsupportedMimeTypeException;
import com.opsmind.attachment.application.port.in.FetchAttachmentContentUseCase;
import com.opsmind.attachment.application.port.in.FindAttachmentUseCase;
import com.opsmind.attachment.application.port.in.RequesterContext;
import com.opsmind.attachment.application.port.in.UploadAttachmentCommand;
import com.opsmind.attachment.application.port.in.UploadAttachmentUseCase;
import com.opsmind.attachment.application.port.out.AttachmentRepository;
import com.opsmind.attachment.application.port.out.ObjectStoragePort;
import com.opsmind.attachment.config.AttachmentProperties;
import com.opsmind.attachment.domain.Attachment;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * SPEC-EP-010/011's own real, server-side implementation. Upload is synchronous
 * end-to-end (validate -> store to MinIO -> persist metadata) — see
 * {@code V001__create_attachments_table.sql}'s own comment for why no row is ever
 * persisted in a non-READY state today.
 */
@Service
public class AttachmentService implements UploadAttachmentUseCase, FindAttachmentUseCase, FetchAttachmentContentUseCase {

    // Anything not [A-Za-z0-9._-] is replaced — the real security-relevant reason
    // this exists is to keep a client-supplied filename from ever influencing the
    // MinIO object key's own path structure (no "/", no null bytes). "." itself
    // stays allowed (a real file extension needs it), which is exactly why
    // REPEATED_DOTS below also has to run — see sanitizeFilename()'s own comment.
    private static final Pattern UNSAFE_FILENAME_CHARS = Pattern.compile("[^A-Za-z0-9._-]");
    private static final Pattern REPEATED_DOTS = Pattern.compile("\\.{2,}");

    private final AttachmentRepository repository;
    private final ObjectStoragePort objectStoragePort;
    private final AttachmentProperties properties;
    private final Clock clock;

    public AttachmentService(AttachmentRepository repository, ObjectStoragePort objectStoragePort, AttachmentProperties properties, Clock clock) {
        this.repository = repository;
        this.objectStoragePort = objectStoragePort;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Attachment upload(UploadAttachmentCommand command) {
        if (!properties.allowedMimeTypes().contains(command.mimeType())) {
            throw new UnsupportedMimeTypeException(command.mimeType());
        }
        if (command.content().length > properties.maxSizeBytes()) {
            throw new AttachmentTooLargeException(command.content().length, properties.maxSizeBytes());
        }

        UUID attachmentId = UUID.randomUUID();
        String objectKey = attachmentId + "/" + sanitizeFilename(command.filename());
        objectStoragePort.put(objectKey, command.content(), command.mimeType());

        Attachment attachment = Attachment.createReady(
            attachmentId, command.filename(), command.mimeType(), command.content().length, objectKey, command.uploadedBy(), clock.instant()
        );
        return repository.save(attachment);
    }

    @Override
    public Attachment findById(UUID attachmentId, RequesterContext requester) {
        Attachment attachment = repository.findById(attachmentId).orElseThrow(() -> new AttachmentNotFoundException(attachmentId));
        authorize(attachment, requester);
        return attachment;
    }

    @Override
    public AttachmentContent fetchContent(UUID attachmentId, RequesterContext requester) {
        Attachment attachment = findById(attachmentId, requester);
        byte[] bytes = objectStoragePort.get(attachment.objectKey());
        return new AttachmentContent(bytes, attachment.mimeType(), attachment.filename());
    }

    /**
     * A real, previously-self-flagged gap: every authenticated caller could read any
     * attachment by ID, regardless of who uploaded it. Closed by allowing exactly 2
     * requesters: the employee who uploaded it (subject match), or a non-EMPLOYEE
     * (service-identity) actor — the same "any non-EMPLOYEE actor_type" rule
     * ticket-workflow-service's own TriageTicketController already applies for a
     * genuinely service-to-service call, reused here for agent-runtime-service's own
     * multimodal HttpAttachmentClient, which authenticates via its own SPEC-ARO-043
     * outbound service identity, never the employee's own token (SendMessageCommand
     * carries no forwarded bearer token of its own).
     */
    private void authorize(Attachment attachment, RequesterContext requester) {
        boolean isOwner = attachment.uploadedBy().equals(requester.subject());
        if (!isOwner && requester.isEmployee()) {
            throw new AttachmentAccessDeniedException(attachment.attachmentId());
        }
    }

    private static String sanitizeFilename(String filename) {
        String base = filename == null || filename.isBlank() ? "file" : filename;
        String withoutUnsafeChars = UNSAFE_FILENAME_CHARS.matcher(base).replaceAll("_");
        // A real, self-caught bug: the character-class substitution above allows "."
        // (needed for a real file extension), so a path-traversal filename like
        // "../../etc/passwd.png" survived as ".._.._etc_passwd.png" — the ".."
        // sequences themselves were never touched. Collapsing any run of 2+ dots
        // closes that without forbidding a single "." anywhere.
        return REPEATED_DOTS.matcher(withoutUnsafeChars).replaceAll("_");
    }
}
