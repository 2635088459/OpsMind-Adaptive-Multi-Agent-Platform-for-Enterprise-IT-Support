package com.opsmind.identity.api.error;

import com.opsmind.identity.api.IdentityRequestContext;
import com.opsmind.identity.application.exception.BreakGlassActivationDeniedException;
import com.opsmind.identity.application.exception.BreakGlassGrantNotFoundException;
import com.opsmind.identity.application.exception.IdpUnavailableException;
import com.opsmind.identity.application.exception.PermissionDeniedException;
import com.opsmind.identity.application.exception.RoleAssignmentNotFoundException;
import com.opsmind.identity.application.exception.RoleGrantOverreachException;
import com.opsmind.identity.application.exception.ServiceIdentityNotFoundException;
import com.opsmind.identity.application.exception.StepUpBindingMismatchException;
import com.opsmind.identity.application.exception.StepUpChallengeNotFoundException;
import com.opsmind.identity.application.exception.StepUpEvidenceRejectedException;
import com.opsmind.identity.application.exception.TokenReplayDetectedException;
import com.opsmind.identity.application.exception.TraceAccessDeniedException;
import com.opsmind.identity.application.exception.TraceNotFoundException;
import com.opsmind.identity.application.exception.TraceQueryUnavailableException;
import com.opsmind.identity.application.exception.UserIdentityNotEligibleException;
import com.opsmind.identity.application.exception.UserIdentityNotFoundException;
import com.opsmind.identity.application.exception.UserSessionNotFoundException;
import com.opsmind.identity.application.exception.WorkloadIdentityNotTrustedException;
import com.opsmind.identity.domain.shared.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps application and domain exceptions to the approved error envelope
 * (05-api-contracts §Error envelope). Never exposes stack traces, internal
 * exception class names, or token-validation internals.
 */
@RestControllerAdvice
public class GlobalRestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalRestExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBeanValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR", "The request is invalid.", false, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR", "The request body is invalid.", false, request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR", "The request is invalid.", false, request);
    }

    @ExceptionHandler(RequestValidationException.class)
    public ResponseEntity<ErrorResponse> handleRequestValidation(RequestValidationException ex, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", ex.getMessage(), false, request);
    }

    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<ErrorResponse> handleMethodSecurityDenied(AuthorizationDeniedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "The actor is not authorized to perform this action.", false, request);
    }

    @ExceptionHandler(UserIdentityNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserIdentityNotFound(UserIdentityNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "USER_IDENTITY_NOT_FOUND", "The user identity was not found.", false, request);
    }

    @ExceptionHandler(UserIdentityNotEligibleException.class)
    public ResponseEntity<ErrorResponse> handleUserIdentityNotEligible(UserIdentityNotEligibleException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "USER_IDENTITY_NOT_ELIGIBLE", "The user identity is not eligible for this action.", false, request);
    }

    @ExceptionHandler(RoleAssignmentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRoleAssignmentNotFound(RoleAssignmentNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "ROLE_ASSIGNMENT_NOT_FOUND", "The role assignment was not found.", false, request);
    }

    @ExceptionHandler(UserSessionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserSessionNotFound(UserSessionNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "USER_SESSION_NOT_FOUND", "The user session was not found.", false, request);
    }

    @ExceptionHandler(StepUpChallengeNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleStepUpChallengeNotFound(StepUpChallengeNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "STEPUP_CHALLENGE_NOT_FOUND", "The step-up challenge was not found.", false, request);
    }

    /** 04-use-cases §Step-up: "Reject ... binding mismatch" (SPEC-UA-017) — 409, a state conflict rather than a validation error. */
    @ExceptionHandler(StepUpBindingMismatchException.class)
    public ResponseEntity<ErrorResponse> handleStepUpBindingMismatch(StepUpBindingMismatchException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "STEPUP_BINDING_MISMATCH", "The step-up challenge is bound to a different action or resource.", false, request);
    }

    /**
     * SPEC-UA-023: found while building this spec's own real HTTP step-up
     * round trip — {@code StepUpEvidenceRejectedException} (thrown by
     * {@code ManageStepUpService#verify} on a wrong subject, wrong/replayed
     * nonce, or insufficient achieved assurance, SPEC-UA-018) never had a
     * handler at all, so it fell through to the generic {@code
     * Exception}-catch-all and returned 500 INTERNAL_SERVER_ERROR — an
     * expected, client-triggerable rejection surfacing as an "unexpected
     * server error" is a real defect, not a stylistic nit: it defeats
     * 500-rate alerting and never tells a legitimate caller its evidence
     * was rejected. 403, matching this codebase's own established
     * convention for "the caller's own proof/authority was insufficient"
     * ({@code WorkloadIdentityNotTrustedException}, {@code
     * BreakGlassActivationDeniedException}).
     */
    @ExceptionHandler(StepUpEvidenceRejectedException.class)
    public ResponseEntity<ErrorResponse> handleStepUpEvidenceRejected(StepUpEvidenceRejectedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "STEPUP_EVIDENCE_REJECTED", "The presented step-up evidence was rejected.", false, request);
    }

    /** SPEC-UA-034 (11-security: "token substitution/replay/theft"). 409, non-retryable — the caller must obtain a fresh token, not retry with the same one. */
    @ExceptionHandler(TokenReplayDetectedException.class)
    public ResponseEntity<ErrorResponse> handleTokenReplayDetected(TokenReplayDetectedException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "TOKEN_REPLAY_DETECTED", "This token has already been used to start a session.", false, request);
    }

    @ExceptionHandler(ServiceIdentityNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleServiceIdentityNotFound(ServiceIdentityNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "SERVICE_IDENTITY_NOT_FOUND", "The service identity was not found.", false, request);
    }

    /** 05-api-contracts: 403 "trusted identity without authority" — the caller's JWT is authenticated but does not resolve to a trusted workload. */
    @ExceptionHandler(WorkloadIdentityNotTrustedException.class)
    public ResponseEntity<ErrorResponse> handleWorkloadIdentityNotTrusted(WorkloadIdentityNotTrustedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "WORKLOAD_IDENTITY_NOT_TRUSTED", "The caller is not a trusted workload identity.", false, request);
    }

    /** 05-api-contracts: 403 "trusted identity without authority" (SPEC-UA-011). */
    @ExceptionHandler(PermissionDeniedException.class)
    public ResponseEntity<ErrorResponse> handlePermissionDenied(PermissionDeniedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "The actor is not authorized to perform this action.", false, request);
    }

    /** 04-use-cases §Grant/revoke role: "Overreach ... returns 403" (SPEC-UA-012, 02-business-invariants #9). */
    @ExceptionHandler(RoleGrantOverreachException.class)
    public ResponseEntity<ErrorResponse> handleRoleGrantOverreach(RoleGrantOverreachException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "ROLE_GRANT_OVERREACH", "The grantor cannot delegate a role beyond its own grant scope.", false, request);
    }

    @ExceptionHandler(BreakGlassGrantNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleBreakGlassGrantNotFound(BreakGlassGrantNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "BREAK_GLASS_GRANT_NOT_FOUND", "The break-glass grant was not found.", false, request);
    }

    /** 11-security: "Break-glass requires strong authentication, domain-06 approval/dual control, bounded scope/time" (SPEC-UA-019). */
    @ExceptionHandler(BreakGlassActivationDeniedException.class)
    public ResponseEntity<ErrorResponse> handleBreakGlassActivationDenied(BreakGlassActivationDeniedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "BREAK_GLASS_ACTIVATION_DENIED", "Break-glass activation preconditions were not met.", false, request);
    }

    /**
     * SPEC-UA-032 (10-failure-handling: "Keycloak unavailable ... new
     * login, step-up, and sensitive actions return 503/fail closed") — 503,
     * retryable, unlike every other denial in this file, since this
     * reflects transient infrastructure distrust rather than a permanent
     * lack of authority.
     */
    @ExceptionHandler(IdpUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleIdpUnavailable(IdpUnavailableException ex, HttpServletRequest request) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, "IDP_UNAVAILABLE", "The identity provider is currently unavailable.", true, request);
    }

    /** SPEC-SC-014: the trace-waterfall proxy is support-console-only. */
    @ExceptionHandler(TraceAccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleTraceAccessDenied(TraceAccessDeniedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "TRACE_ACCESS_DENIED", "This session is not authorized to query traces.", false, request);
    }

    /** SPEC-SC-014 §16: a real, clean absence — not found under any queried tenant (e.g. outside Tempo's retention window). */
    @ExceptionHandler(TraceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTraceNotFound(TraceNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "TRACE_NOT_FOUND", "The trace was not found.", false, request);
    }

    /** SPEC-SC-014: every queried tenant failed to respond (Tempo/network unreachable) — retryable, distinct from a genuine not-found. */
    @ExceptionHandler(TraceQueryUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleTraceQueryUnavailable(TraceQueryUnavailableException ex, HttpServletRequest request) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, "TRACE_QUERY_UNAVAILABLE", "The trace store is currently unavailable.", true, request);
    }

    /** Covers every domain-rule violation (illegal state transition) uniformly via its own {@code code()}; 409 since it is a state conflict, not a validation error. */
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ErrorResponse> handleDomainException(DomainException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.code(), ex.getMessage(), false, request);
    }

    /**
     * 04-use-cases §Grant/revoke role: "Overreach or overlap returns
     * 403/409" — the 409 half. A genuinely concurrent grant race can slip
     * past the application-level idempotent-return-existing check (SPEC-UA-001)
     * and hit the real DB partial-unique-index ({@code
     * uq_role_assignments_active}, 03-state-machine: "Overlapping ACTIVE
     * assignments for the same user, role, and scope are prevented by
     * constraint plus transactional validation") directly; that surfaces
     * here as a state conflict, not an unexpected server error.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "CONFLICT", "The request conflicts with the current state.", true, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("unexpected error handling {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred.", true, request);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message, boolean retryable, HttpServletRequest request) {
        String correlationId = request.getHeader(IdentityRequestContext.CORRELATION_ID_HEADER);
        ErrorResponse body = ErrorResponse.of(code, message, correlationId == null ? "" : correlationId, retryable);
        return ResponseEntity.status(status).body(body);
    }
}
