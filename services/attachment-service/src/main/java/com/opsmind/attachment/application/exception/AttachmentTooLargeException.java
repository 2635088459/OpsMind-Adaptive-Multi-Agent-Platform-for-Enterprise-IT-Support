package com.opsmind.attachment.application.exception;

public class AttachmentTooLargeException extends RuntimeException {

    public AttachmentTooLargeException(long sizeBytes, long maxSizeBytes) {
        super("file too large: " + sizeBytes + " bytes (max " + maxSizeBytes + " bytes)");
    }
}
