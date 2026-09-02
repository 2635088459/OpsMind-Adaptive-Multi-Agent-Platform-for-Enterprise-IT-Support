package com.opsmind.attachment.api;

import com.opsmind.attachment.domain.Attachment;

import java.time.Instant;

/** 09-employee-portal's own {@code 01-domain-model} §"Attachment" shape exactly (camelCase; {@code objectRef} is deliberately never exposed — see that field's own domain-layer comment). */
public record AttachmentResponse(
    String attachmentId,
    String filename,
    String mimeType,
    long sizeBytes,
    String thumbnailUrl,
    String uploadStatus,
    Instant createdAt
) {

    public static AttachmentResponse from(Attachment attachment) {
        return new AttachmentResponse(
            attachment.attachmentId().toString(), attachment.filename(), attachment.mimeType(), attachment.sizeBytes(),
            attachment.thumbnailUrl(), attachment.status().name().toLowerCase(), attachment.createdAt()
        );
    }
}
