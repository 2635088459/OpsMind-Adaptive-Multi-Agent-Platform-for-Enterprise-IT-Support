package com.opsmind.attachment.domain;

/**
 * Mirrors 09-employee-portal's own {@code 01-domain-model} §"Attachment"
 * {@code uploadStatus} exactly ({@code "uploading" | "ready" | "failed"}, upper-cased
 * here to match this codebase's own enum convention). This service's own upload
 * endpoint is synchronous end-to-end — see {@code V001__create_attachments_table.sql}'s
 * own comment for why {@link #READY} is the only value a real row is ever persisted
 * with today.
 */
public enum AttachmentStatus {
    UPLOADING,
    READY,
    FAILED
}
