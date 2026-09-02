package com.opsmind.attachment.application.port.in;

/** {@code content} is read fully into memory before this command is built (the real ceiling is {@code spring.servlet.multipart.max-file-size}, 30 MB, comfortably above the real 25 MB business limit this service enforces itself — see application.yml's own comment) — acceptable for this size class; a genuinely large-file/streaming design is out of this v1's scope. */
public record UploadAttachmentCommand(
    String filename,
    String mimeType,
    byte[] content,
    String uploadedBy
) {
}
