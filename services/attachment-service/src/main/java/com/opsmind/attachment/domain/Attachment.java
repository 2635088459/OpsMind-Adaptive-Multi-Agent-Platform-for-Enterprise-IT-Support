package com.opsmind.attachment.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 09-employee-portal's own {@code 01-domain-model} §"Attachment" entity, now real.
 * {@code objectKey} is the real MinIO object key {@code objectRef} points at — never
 * exposed directly over the API (the API surfaces only {@code attachmentId} as the
 * opaque {@code ref}, matching {@code useUploadAttachment}'s own already-built
 * contract: {@code {ref: string}}).
 */
public record Attachment(
    UUID attachmentId,
    String filename,
    String mimeType,
    long sizeBytes,
    String objectKey,
    String thumbnailUrl,
    AttachmentStatus status,
    String uploadedBy,
    Instant createdAt,
    Instant updatedAt
) {

    public static Attachment createReady(
        UUID attachmentId, String filename, String mimeType, long sizeBytes, String objectKey, String uploadedBy, Instant now
    ) {
        return new Attachment(attachmentId, filename, mimeType, sizeBytes, objectKey, null, AttachmentStatus.READY, uploadedBy, now, now);
    }
}
