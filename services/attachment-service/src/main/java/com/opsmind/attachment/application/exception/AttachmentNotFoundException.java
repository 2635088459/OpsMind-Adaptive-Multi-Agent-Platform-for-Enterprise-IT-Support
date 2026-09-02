package com.opsmind.attachment.application.exception;

import java.util.UUID;

public class AttachmentNotFoundException extends RuntimeException {

    public AttachmentNotFoundException(UUID attachmentId) {
        super("attachment " + attachmentId + " was not found");
    }
}
