package com.opsmind.attachment.platform.error;

import com.opsmind.attachment.application.exception.AttachmentAccessDeniedException;
import com.opsmind.attachment.application.exception.AttachmentNotFoundException;
import com.opsmind.attachment.application.exception.AttachmentTooLargeException;
import com.opsmind.attachment.application.exception.ObjectStorageUnavailableException;
import com.opsmind.attachment.application.exception.UnsupportedMimeTypeException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Maps application exceptions to the shared error envelope, mirroring every other Java service in this platform's own GlobalRestExceptionHandler exactly. */
@RestControllerAdvice
public class GlobalRestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalRestExceptionHandler.class);

    @ExceptionHandler(UnsupportedMimeTypeException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMimeType(UnsupportedMimeTypeException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_MIME_TYPE", ex.getMessage(), request);
    }

    @ExceptionHandler(AttachmentTooLargeException.class)
    public ResponseEntity<ErrorResponse> handleTooLarge(AttachmentTooLargeException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "ATTACHMENT_TOO_LARGE", ex.getMessage(), request);
    }

    /** The multipart container's own hard ceiling (application.yml's spring.servlet.multipart.max-file-size) — a real, distinct rejection from the business-level AttachmentTooLargeException above. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "ATTACHMENT_TOO_LARGE", "The uploaded file exceeds the maximum allowed size.", request);
    }

    @ExceptionHandler(AttachmentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(AttachmentNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(AttachmentAccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AttachmentAccessDeniedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "ATTACHMENT_ACCESS_DENIED", ex.getMessage(), request);
    }

    @ExceptionHandler(ObjectStorageUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleStorageUnavailable(ObjectStorageUnavailableException ex, HttpServletRequest request) {
        log.error("object storage call failed", ex);
        return build(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "A required dependency is unavailable.", request);
    }

    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAuthorizationDenied(AuthorizationDeniedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "The actor is not authorized to perform this action.", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("unexpected error handling {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred.", request);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message, HttpServletRequest request) {
        String traceId = MDC.get("traceId");
        String correlationId = request.getHeader("X-Correlation-Id");
        ErrorResponse body = ErrorResponse.of(code, message, traceId == null ? "" : traceId, correlationId == null ? "" : correlationId);
        return ResponseEntity.status(status).body(body);
    }
}
