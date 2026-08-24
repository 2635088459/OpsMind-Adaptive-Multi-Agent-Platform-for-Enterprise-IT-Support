package com.opsmind.identity.api.error;

import com.opsmind.identity.api.IdentityRequestContext;
import com.opsmind.identity.application.exception.RoleAssignmentNotFoundException;
import com.opsmind.identity.application.exception.ServiceIdentityNotFoundException;
import com.opsmind.identity.application.exception.StepUpChallengeNotFoundException;
import com.opsmind.identity.application.exception.UserIdentityNotEligibleException;
import com.opsmind.identity.application.exception.UserIdentityNotFoundException;
import com.opsmind.identity.application.exception.UserSessionNotFoundException;
import com.opsmind.identity.domain.shared.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    @ExceptionHandler(ServiceIdentityNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleServiceIdentityNotFound(ServiceIdentityNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "SERVICE_IDENTITY_NOT_FOUND", "The service identity was not found.", false, request);
    }

    /** Covers every domain-rule violation (illegal state transition) uniformly via its own {@code code()}; 409 since it is a state conflict, not a validation error. */
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ErrorResponse> handleDomainException(DomainException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.code(), ex.getMessage(), false, request);
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
