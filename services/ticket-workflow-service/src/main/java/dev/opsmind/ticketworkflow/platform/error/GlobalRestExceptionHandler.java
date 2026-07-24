package dev.opsmind.ticketworkflow.platform.error;

import dev.opsmind.ticketworkflow.ticket.api.exception.RequestValidationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.DisplayIdGenerationExhaustedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.EventSchemaValidationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps application and validation exceptions to the approved error envelope
 * (SPEC-TW-001 §21). Never exposes stack traces, SQL, table/constraint
 * names, internal exception classes, raw JWTs, passwords, or connection
 * strings.
 */
@RestControllerAdvice
public class GlobalRestExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBeanValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "The request is invalid.", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "The request body is invalid.", request);
    }

    @ExceptionHandler(RequestValidationException.class)
    public ResponseEntity<ErrorResponse> handleRequestValidation(RequestValidationException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage(), request);
    }

    @ExceptionHandler(TicketAuthorizationException.class)
    public ResponseEntity<ErrorResponse> handleAuthorization(TicketAuthorizationException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "The actor is not authorized to perform this action.", request);
    }

    /**
     * {@code @PreAuthorize} denials throw this from the method-security AOP
     * interceptor around the controller invocation, which Spring MVC's own
     * exception resolution (this advice) handles before the exception could
     * ever reach Spring Security's filter-level AccessDeniedHandler.
     */
    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<ErrorResponse> handleMethodSecurityDenied(AuthorizationDeniedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "The actor is not authorized to perform this action.", request);
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ResponseEntity<ErrorResponse> handleKeyReused(IdempotencyKeyReusedException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "The idempotency key was already used with a different request.", request);
    }

    @ExceptionHandler(RequestInProgressException.class)
    public ResponseEntity<ErrorResponse> handleRequestInProgress(RequestInProgressException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .header(HttpHeaders.RETRY_AFTER, "1")
            .body(bodyOf("REQUEST_IN_PROGRESS", "An identical request is already being processed.", request));
    }

    @ExceptionHandler({DisplayIdGenerationExhaustedException.class, EventSchemaValidationException.class})
    public ResponseEntity<ErrorResponse> handleInternalFailure(RuntimeException ex, HttpServletRequest request) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred.", request);
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    public ResponseEntity<ErrorResponse> handleDependencyUnavailable(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "A required dependency is unavailable.", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred.", request);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(bodyOf(code, message, request));
    }

    private ErrorResponse bodyOf(String code, String message, HttpServletRequest request) {
        String traceId = MDC.get("traceId");
        String correlationId = request.getHeader("X-Correlation-Id");
        return ErrorResponse.of(code, message, traceId == null ? "" : traceId, correlationId == null ? "" : correlationId);
    }
}
