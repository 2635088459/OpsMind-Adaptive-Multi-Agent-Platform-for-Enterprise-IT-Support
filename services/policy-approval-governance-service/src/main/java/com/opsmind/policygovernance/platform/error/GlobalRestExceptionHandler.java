package com.opsmind.policygovernance.platform.error;

import com.opsmind.policygovernance.api.exception.RequestValidationException;
import com.opsmind.policygovernance.application.exception.ApprovalAlreadyCancelledException;
import com.opsmind.policygovernance.application.exception.ApprovalAlreadyDecidedException;
import com.opsmind.policygovernance.application.exception.ApprovalNotAuthorizedException;
import com.opsmind.policygovernance.application.exception.ApprovalRequestNotFoundException;
import com.opsmind.policygovernance.application.exception.DecisionKeyConflictException;
import com.opsmind.policygovernance.application.exception.DuplicateApprovalRequestException;
import com.opsmind.policygovernance.application.exception.InvalidOverrideRequestException;
import com.opsmind.policygovernance.application.exception.OutboxEventNotFailedException;
import com.opsmind.policygovernance.application.exception.OutboxEventNotFoundException;
import com.opsmind.policygovernance.application.exception.OverrideAlreadyRevokedException;
import com.opsmind.policygovernance.application.exception.OverrideAlreadyUsedException;
import com.opsmind.policygovernance.application.exception.PolicyDecisionNotFoundException;
import com.opsmind.policygovernance.application.exception.PolicyNotFoundException;
import com.opsmind.policygovernance.application.exception.PolicyPublishSeparationOfDutiesException;
import com.opsmind.policygovernance.application.exception.PolicyVersionNotFoundException;
import com.opsmind.policygovernance.application.exception.ProcessedEventNotFoundException;
import com.opsmind.policygovernance.domain.approval.SeparationOfDutiesNotVerifiedException;
import com.opsmind.policygovernance.domain.shared.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps application and domain exceptions to the approved error envelope.
 * Never exposes stack traces, internal exception class names, or (per
 * api-contract) any sensitive raw input.
 */
@RestControllerAdvice
public class GlobalRestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalRestExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBeanValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "The request is invalid.", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "The request body is invalid.", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "The request is invalid.", request);
    }

    @ExceptionHandler(RequestValidationException.class)
    public ResponseEntity<ErrorResponse> handleRequestValidation(RequestValidationException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage(), request);
    }

    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<ErrorResponse> handleMethodSecurityDenied(AuthorizationDeniedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "The actor is not authorized to perform this action.", request);
    }

    @ExceptionHandler(ApprovalNotAuthorizedException.class)
    public ResponseEntity<ErrorResponse> handleApprovalNotAuthorized(ApprovalNotAuthorizedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "APPROVAL_NOT_AUTHORIZED", "The actor is not authorized to decide this approval request.", request);
    }

    @ExceptionHandler(PolicyPublishSeparationOfDutiesException.class)
    public ResponseEntity<ErrorResponse> handlePolicyPublishSeparationOfDuties(PolicyPublishSeparationOfDutiesException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "POLICY_PUBLISH_SEPARATION_OF_DUTIES", "A policy version cannot be published by its own author.", request);
    }

    @ExceptionHandler(SeparationOfDutiesNotVerifiedException.class)
    public ResponseEntity<ErrorResponse> handleSeparationOfDutiesNotVerified(SeparationOfDutiesNotVerifiedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, ex.code(), "The approval cannot be granted without a passed separation-of-duties check.", request);
    }

    @ExceptionHandler(PolicyNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePolicyNotFound(PolicyNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "POLICY_NOT_FOUND", "The policy was not found.", request);
    }

    @ExceptionHandler(PolicyVersionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePolicyVersionNotFound(PolicyVersionNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "POLICY_VERSION_NOT_FOUND", "The policy version was not found.", request);
    }

    @ExceptionHandler(PolicyDecisionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePolicyDecisionNotFound(PolicyDecisionNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "POLICY_DECISION_NOT_FOUND", "The policy decision was not found.", request);
    }

    @ExceptionHandler(ApprovalRequestNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleApprovalRequestNotFound(ApprovalRequestNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "APPROVAL_REQUEST_NOT_FOUND", "The approval request was not found.", request);
    }

    @ExceptionHandler(DuplicateApprovalRequestException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateApprovalRequest(DuplicateApprovalRequestException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "DUPLICATE_APPROVAL_REQUEST", "The requestKey was already used with a different request payload.", request);
    }

    @ExceptionHandler(DecisionKeyConflictException.class)
    public ResponseEntity<ErrorResponse> handleDecisionKeyConflict(DecisionKeyConflictException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "DECISION_KEY_CONFLICT", "The decisionKey was already used with a different inputHash.", request);
    }

    @ExceptionHandler(ApprovalAlreadyDecidedException.class)
    public ResponseEntity<ErrorResponse> handleApprovalAlreadyDecided(ApprovalAlreadyDecidedException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "APPROVAL_ALREADY_DECIDED", "The approval request already has a final decision that does not match this request.", request);
    }

    /** SPEC-PG-012: the cancel-command analog of {@link #handleApprovalAlreadyDecided}. */
    @ExceptionHandler(ApprovalAlreadyCancelledException.class)
    public ResponseEntity<ErrorResponse> handleApprovalAlreadyCancelled(ApprovalAlreadyCancelledException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "APPROVAL_ALREADY_CANCELLED", "The approval request is already cancelled by a different attempt.", request);
    }

    /** SPEC-PG-022: the use-command analog of {@link #handleApprovalAlreadyCancelled}. */
    @ExceptionHandler(OverrideAlreadyUsedException.class)
    public ResponseEntity<ErrorResponse> handleOverrideAlreadyUsed(OverrideAlreadyUsedException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "OVERRIDE_ALREADY_USED", "The override is already used by a different attempt.", request);
    }

    /** SPEC-PG-022: the revoke-command analog of {@link #handleApprovalAlreadyCancelled}. */
    @ExceptionHandler(OverrideAlreadyRevokedException.class)
    public ResponseEntity<ErrorResponse> handleOverrideAlreadyRevoked(OverrideAlreadyRevokedException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "OVERRIDE_ALREADY_REVOKED", "The override is already revoked by a different attempt.", request);
    }

    /** SPEC-PG-022: a {@code POLICY_OVERRIDE} request missing its required scope/expiry binding (UC-PG-006). */
    @ExceptionHandler(InvalidOverrideRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidOverrideRequest(InvalidOverrideRequestException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_OVERRIDE_REQUEST", ex.getMessage(), request);
    }

    /** SPEC-PG-024: requeue targets an outbox event id that does not exist. */
    @ExceptionHandler(OutboxEventNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleOutboxEventNotFound(OutboxEventNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "OUTBOX_EVENT_NOT_FOUND", "The outbox event was not found.", request);
    }

    /** SPEC-PG-024: requeue targets an outbox event that is not currently FAILED (dead-lettered). */
    @ExceptionHandler(OutboxEventNotFailedException.class)
    public ResponseEntity<ErrorResponse> handleOutboxEventNotFailed(OutboxEventNotFailedException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "OUTBOX_EVENT_NOT_FAILED", "Only a dead-lettered (FAILED) outbox event can be requeued.", request);
    }

    @ExceptionHandler(ProcessedEventNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleProcessedEventNotFound(ProcessedEventNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "PROCESSED_EVENT_NOT_FOUND", "That event was not recorded as processed by that consumer.", request);
    }

    /**
     * Defensive fallback: every known race (duplicate decision key, duplicate
     * approval request, concurrent grant/deny) is caught and translated
     * above before it can reach the database's own unique constraint. This
     * only fires if a future caller adds a new write path without the same
     * check — never expose the constraint name or table to the client.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("unhandled data integrity violation on {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.CONFLICT, "CONFLICT", "The request conflicts with an existing record.", request);
    }

    /** Covers the remaining domain-rule violations (illegal state transition, request mismatch, immutable version) uniformly via their own {@code code()}. */
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ErrorResponse> handleDomainException(DomainException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.code(), ex.getMessage(), request);
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
