package com.opsmind.attachment.api;

/** Matches employee-portal's own already-built {@code uploadAttachment()} contract exactly: {@code Promise<{ref: string}>} (SPEC-EP-010 §13). {@code ref} is the real {@code attachmentId} — the one opaque identifier {@code attachmentRefs} carries downstream into SPEC-ARO-039's own SendMessageCommand. */
public record AttachmentUploadResponse(String ref) {
}
