package com.opsmind.attachment.application.exception;

import java.util.UUID;

/**
 * A real, previously-self-flagged gap now closed: an authenticated caller read this
 * attachment's metadata/content without being its uploader and without a legitimate
 * non-EMPLOYEE (service-identity) actor_type — see {@code AttachmentService#authorize}
 * for the exact rule.
 */
public class AttachmentAccessDeniedException extends RuntimeException {

    public AttachmentAccessDeniedException(UUID attachmentId) {
        super("attachment " + attachmentId + " is not accessible to this requester");
    }
}
