package dev.opsmind.ticketworkflow.platform.error;

import dev.opsmind.ticketworkflow.ticket.api.exception.PreconditionRequiredException;
import dev.opsmind.ticketworkflow.ticket.api.exception.RequestValidationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.AssigneeInactiveException;
import dev.opsmind.ticketworkflow.ticket.application.exception.AssigneeNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.AssigneeNotInQueueException;
import dev.opsmind.ticketworkflow.ticket.application.exception.AssigneeNotSupportAgentException;
import dev.opsmind.ticketworkflow.ticket.application.exception.ApprovalRequestAlreadyOpenException;
import dev.opsmind.ticketworkflow.ticket.application.exception.CompensationConflictException;
import dev.opsmind.ticketworkflow.ticket.application.exception.CorrectionEventConflictException;
import dev.opsmind.ticketworkflow.ticket.application.exception.DataIntegrityRepairConflictException;
import dev.opsmind.ticketworkflow.ticket.application.exception.DisplayIdGenerationExhaustedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.EventSchemaValidationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.FilterOutsideAuthorizedScopeException;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.IntegrityRepairSourceNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.InvalidCursorException;
import dev.opsmind.ticketworkflow.ticket.application.exception.QueueAccessDeniedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.ReconciliationCaseConflictException;
import dev.opsmind.ticketworkflow.ticket.application.exception.ReplayEventConflictException;
import dev.opsmind.ticketworkflow.ticket.application.exception.ReplaySourceEventNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.exception.ResolutionCycleAlreadyCompletedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.ResolutionCycleNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.SecretDetectionFailClosedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.SecretDetectionPolicyConflictException;
import dev.opsmind.ticketworkflow.ticket.application.exception.SecretDetectionPolicyDeniedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.SensitiveReadAuditFailureException;
import dev.opsmind.ticketworkflow.ticket.application.exception.SensitiveReadAuditPolicyConflictException;
import dev.opsmind.ticketworkflow.ticket.application.exception.SensitiveReadAuditPolicyDeniedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.StepUpAuthenticationFailClosedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.StepUpAuthenticationPolicyConflictException;
import dev.opsmind.ticketworkflow.ticket.application.exception.StepUpAuthenticationRequiredException;
import dev.opsmind.ticketworkflow.ticket.application.exception.SupportQueueAuthorizationConflictException;
import dev.opsmind.ticketworkflow.ticket.application.exception.SupportQueueAuthorizationDeniedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.SupportQueueAuthorizationFailClosedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.SupportQueueInvalidException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketMessageNotAllowedInStateException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketVersionConflictException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TriageCategoryInvalidException;
import dev.opsmind.ticketworkflow.ticket.application.exception.UserInputRequestAlreadyOpenException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TriageNotAllowedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TriageSubcategoryInvalidException;
import dev.opsmind.ticketworkflow.ticket.application.exception.VerificationAttemptAlreadyActiveException;
import dev.opsmind.ticketworkflow.ticket.application.exception.VerificationEvidenceRequiredException;
import dev.opsmind.ticketworkflow.ticket.application.exception.VerificationToolResultInvalidException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.AssigneeRequiredForCurrentStatusException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.AssignmentRequiresAChangeException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.AutoCloseNotYetDueException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidTicketStateException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidStatusTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidTicketTransitionException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.ReassignmentRequiresDifferentAssigneeException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.TicketAlreadyAssignedException;
import dev.opsmind.ticketworkflow.ticket.domain.exception.TicketNotAssignedException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/**
 * Maps application and validation exceptions to the approved error envelope
 * (SPEC-TW-001 §21). Never exposes stack traces, SQL, table/constraint
 * names, internal exception classes, raw JWTs, passwords, or connection
 * strings.
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

    @ExceptionHandler(RequestValidationException.class)
    public ResponseEntity<ErrorResponse> handleRequestValidation(RequestValidationException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "The request is invalid.", request);
    }

    @ExceptionHandler(TicketAuthorizationException.class)
    public ResponseEntity<ErrorResponse> handleAuthorization(TicketAuthorizationException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "The actor is not authorized to perform this action.", request);
    }

    /**
     * Distinct from {@link TicketAuthorizationException} (missing the Queue
     * scope entirely, 403 FORBIDDEN): this covers a requested filter value
     * that falls outside an otherwise-authorized actor's scope (SPEC-TW-005
     * §7), and never reveals the actor's full authorized scope.
     */
    @ExceptionHandler(FilterOutsideAuthorizedScopeException.class)
    public ResponseEntity<ErrorResponse> handleFilterOutsideAuthorizedScope(FilterOutsideAuthorizedScopeException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "FILTER_OUTSIDE_AUTHORIZED_SCOPE", "One or more requested filters are outside the authorized Support Queue scope.", request);
    }

    @ExceptionHandler(TicketNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTicketNotFound(TicketNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND", "The Ticket was not found.", request);
    }

    @ExceptionHandler(TicketMessageNotAllowedInStateException.class)
    public ResponseEntity<ErrorResponse> handleMessageNotAllowedInState(TicketMessageNotAllowedInStateException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "MESSAGE_NOT_ALLOWED_IN_STATE", "A message cannot be added while the Ticket is in its current state.", request);
    }

    /**
     * Never exposes why the cursor was rejected (malformed, tampered,
     * expired, or bound to different filters/sort/actor) — SPEC-TW-003
     * §15.
     */
    @ExceptionHandler(InvalidCursorException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCursor(InvalidCursorException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "The pagination cursor is invalid or expired.", request);
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

    /** SPEC-TW-007 AC-08: unlike other 4xx handlers, this one deliberately exposes state. */
    @ExceptionHandler(InvalidTicketTransitionException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTicketTransition(InvalidTicketTransitionException ex, HttpServletRequest request) {
        Map<String, Object> details = Map.of(
            "currentStatus", ex.currentStatus().name(),
            "requiredStatus", ex.requiredStatus().name()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(bodyOfWithDetails("INVALID_TICKET_STATE", "The ticket is not in the required status for this operation.", request, details));
    }

    @ExceptionHandler(TriageNotAllowedException.class)
    public ResponseEntity<ErrorResponse> handleTriageNotAllowed(TriageNotAllowedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "TRIAGE_NOT_ALLOWED", "The actor is not permitted to triage tickets.", request);
    }

    @ExceptionHandler(QueueAccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleQueueAccessDenied(QueueAccessDeniedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "QUEUE_ACCESS_DENIED", "The actor is not authorized for the ticket's Support Queue.", request);
    }

    /** SPEC-TW-033: never reveals the actor's authorized Support Queue scope or the evaluated decisionCode. */
    @ExceptionHandler(SupportQueueAuthorizationDeniedException.class)
    public ResponseEntity<ErrorResponse> handleSupportQueueAuthorizationDenied(SupportQueueAuthorizationDeniedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "AUTHORIZATION_DENIED", "The actor is not authorized within the requested Support Queue scope.", request);
    }

    /** SPEC-TW-033 api-contract §"Errors": {@code 409} for a request whose current state/context the policy cannot evaluate. */
    @ExceptionHandler(SupportQueueAuthorizationConflictException.class)
    public ResponseEntity<ErrorResponse> handleSupportQueueAuthorizationConflict(SupportQueueAuthorizationConflictException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "AUTHORIZATION_CONTEXT_CONFLICT", "The current Ticket state or context does not support this authorization request.", request);
    }

    /** SPEC-TW-034: never reveals the actor's eligible read views or the evaluated decisionCode. */
    @ExceptionHandler(SensitiveReadAuditPolicyDeniedException.class)
    public ResponseEntity<ErrorResponse> handleSensitiveReadAuditPolicyDenied(SensitiveReadAuditPolicyDeniedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "AUTHORIZATION_DENIED", "The actor is not eligible for a sensitive Ticket read.", request);
    }

    /** SPEC-TW-034 api-contract §"Errors": {@code 409} for an operation this policy does not govern. */
    @ExceptionHandler(SensitiveReadAuditPolicyConflictException.class)
    public ResponseEntity<ErrorResponse> handleSensitiveReadAuditPolicyConflict(SensitiveReadAuditPolicyConflictException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "AUTHORIZATION_CONTEXT_CONFLICT", "The current Ticket state or context does not support this audit policy request.", request);
    }

    /** SPEC-TW-035: never reveals the matched pattern, the evaluated decisionCode, or any offending text to the client. */
    @ExceptionHandler(SecretDetectionPolicyDeniedException.class)
    public ResponseEntity<ErrorResponse> handleSecretDetectionPolicyDenied(SecretDetectionPolicyDeniedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "SECRET_DETECTED", "The content must not contain secrets or credentials.", request);
    }

    /** SPEC-TW-035 api-contract §"Errors": {@code 409} for an operation this policy does not govern. */
    @ExceptionHandler(SecretDetectionPolicyConflictException.class)
    public ResponseEntity<ErrorResponse> handleSecretDetectionPolicyConflict(SecretDetectionPolicyConflictException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "AUTHORIZATION_CONTEXT_CONFLICT", "The current Ticket state or context does not support this secret detection request.", request);
    }

    /** SPEC-TW-036: never reveals the evaluated decisionCode or any proof detail to the client. */
    @ExceptionHandler(StepUpAuthenticationRequiredException.class)
    public ResponseEntity<ErrorResponse> handleStepUpAuthenticationRequired(StepUpAuthenticationRequiredException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "STEP_UP_REQUIRED", "A valid step-up authentication proof is required for this operation.", request);
    }

    /** SPEC-TW-036 api-contract §"Errors": {@code 409} for an operation this policy does not govern. */
    @ExceptionHandler(StepUpAuthenticationPolicyConflictException.class)
    public ResponseEntity<ErrorResponse> handleStepUpAuthenticationPolicyConflict(StepUpAuthenticationPolicyConflictException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "AUTHORIZATION_CONTEXT_CONFLICT", "The current Ticket state or context does not support this step-up request.", request);
    }

    @ExceptionHandler(TriageCategoryInvalidException.class)
    public ResponseEntity<ErrorResponse> handleTriageCategoryInvalid(TriageCategoryInvalidException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "TRIAGE_CATEGORY_INVALID", "The category does not exist or is not active.", request);
    }

    @ExceptionHandler(TriageSubcategoryInvalidException.class)
    public ResponseEntity<ErrorResponse> handleTriageSubcategoryInvalid(TriageSubcategoryInvalidException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "TRIAGE_SUBCATEGORY_INVALID", "The subcategory does not exist, is not active, or does not belong to the selected category.", request);
    }

    @ExceptionHandler(SupportQueueInvalidException.class)
    public ResponseEntity<ErrorResponse> handleSupportQueueInvalid(SupportQueueInvalidException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "SUPPORT_QUEUE_INVALID", "The support queue does not exist or is not active.", request);
    }

    /** SPEC-TW-007 AC-10: exposes the ticket's current version so the client can render its ETag without a second read. */
    @ExceptionHandler(TicketVersionConflictException.class)
    public ResponseEntity<ErrorResponse> handleTicketVersionConflict(TicketVersionConflictException ex, HttpServletRequest request) {
        Map<String, Object> details = Map.of("currentVersion", ex.currentVersion());
        return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
            .eTag(String.valueOf(ex.currentVersion()))
            .body(bodyOfWithDetails("VERSION_CONFLICT", "The ticket was changed by another operation.", request, details));
    }

    @ExceptionHandler(PreconditionRequiredException.class)
    public ResponseEntity<ErrorResponse> handlePreconditionRequired(PreconditionRequiredException ex, HttpServletRequest request) {
        return build(HttpStatus.PRECONDITION_REQUIRED, "PRECONDITION_REQUIRED", "The If-Match header is required.", request);
    }

    /** SPEC-TW-008 §4: the multi-status counterpart to {@link InvalidTicketTransitionException} (Reassign only). */
    @ExceptionHandler(InvalidTicketStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTicketState(InvalidTicketStateException ex, HttpServletRequest request) {
        Map<String, Object> details = Map.of(
            "currentStatus", ex.currentStatus().name(),
            "allowedStatuses", ex.allowedStatuses().stream().map(Enum::name).toList()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(bodyOfWithDetails("INVALID_TICKET_STATE", "The ticket is not in one of the allowed statuses for this operation.", request, details));
    }

    @ExceptionHandler(InvalidStatusTransitionException.class)
    public ResponseEntity<ErrorResponse> handleInvalidStatusTransition(InvalidStatusTransitionException ex, HttpServletRequest request) {
        Map<String, Object> details = Map.of(
            "currentStatus", ex.currentStatus().name(),
            "targetStatus", ex.targetStatus().name()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(bodyOfWithDetails("INVALID_STATUS_TRANSITION", "The requested ticket status transition is not allowed.", request, details));
    }

    @ExceptionHandler(TicketAlreadyAssignedException.class)
    public ResponseEntity<ErrorResponse> handleTicketAlreadyAssigned(TicketAlreadyAssignedException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "TICKET_ALREADY_ASSIGNED", "The ticket already has an assignee.", request);
    }

    @ExceptionHandler(TicketNotAssignedException.class)
    public ResponseEntity<ErrorResponse> handleTicketNotAssigned(TicketNotAssignedException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "TICKET_NOT_ASSIGNED", "The ticket has no current assignee.", request);
    }

    @ExceptionHandler(AssigneeNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAssigneeNotFound(AssigneeNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "ASSIGNEE_NOT_FOUND", "The assignee does not exist.", request);
    }

    @ExceptionHandler(AssigneeInactiveException.class)
    public ResponseEntity<ErrorResponse> handleAssigneeInactive(AssigneeInactiveException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "ASSIGNEE_INACTIVE", "The assignee is not active.", request);
    }

    @ExceptionHandler(AssigneeNotSupportAgentException.class)
    public ResponseEntity<ErrorResponse> handleAssigneeNotSupportAgent(AssigneeNotSupportAgentException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "ASSIGNEE_NOT_SUPPORT_AGENT", "The assignee does not hold a support-capable role.", request);
    }

    @ExceptionHandler(AssigneeNotInQueueException.class)
    public ResponseEntity<ErrorResponse> handleAssigneeNotInQueue(AssigneeNotInQueueException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "ASSIGNEE_NOT_IN_QUEUE", "The assignee is not a member of the ticket's support queue.", request);
    }

    @ExceptionHandler(ReassignmentRequiresDifferentAssigneeException.class)
    public ResponseEntity<ErrorResponse> handleReassignmentRequiresDifferentAssignee(ReassignmentRequiresDifferentAssigneeException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "The new assignee must differ from the current assignee.", request);
    }

    /** SPEC-TW-030: mirrors {@link ReassignmentRequiresDifferentAssigneeException}'s handler, generalized to team/queue/assignee. */
    @ExceptionHandler(AssignmentRequiresAChangeException.class)
    public ResponseEntity<ErrorResponse> handleAssignmentRequiresAChange(AssignmentRequiresAChangeException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "The requested team, support queue, and assignee are identical to the ticket's current ones.", request);
    }

    @ExceptionHandler(AssigneeRequiredForCurrentStatusException.class)
    public ResponseEntity<ErrorResponse> handleAssigneeRequiredForCurrentStatus(AssigneeRequiredForCurrentStatusException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "The ticket's current status requires an assignee and cannot be routed to an unassigned state.", request);
    }

    @ExceptionHandler(ResolutionCycleNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResolutionCycleNotFound(ResolutionCycleNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "RESOLUTION_CYCLE_NOT_FOUND", "The ticket has no current resolution cycle.", request);
    }

    @ExceptionHandler(ResolutionCycleAlreadyCompletedException.class)
    public ResponseEntity<ErrorResponse> handleResolutionCycleAlreadyCompleted(ResolutionCycleAlreadyCompletedException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "RESOLUTION_CYCLE_ALREADY_COMPLETED", "The current resolution cycle is already completed.", request);
    }

    @ExceptionHandler(UserInputRequestAlreadyOpenException.class)
    public ResponseEntity<ErrorResponse> handleUserInputRequestAlreadyOpen(UserInputRequestAlreadyOpenException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "USER_INPUT_REQUEST_ALREADY_OPEN", "The ticket already has an open user input request.", request);
    }

    @ExceptionHandler(ApprovalRequestAlreadyOpenException.class)
    public ResponseEntity<ErrorResponse> handleApprovalRequestAlreadyOpen(ApprovalRequestAlreadyOpenException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "APPROVAL_REQUEST_ALREADY_OPEN", "The ticket already has an open approval request.", request);
    }

    @ExceptionHandler(VerificationAttemptAlreadyActiveException.class)
    public ResponseEntity<ErrorResponse> handleVerificationAttemptAlreadyActive(VerificationAttemptAlreadyActiveException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "VERIFICATION_ATTEMPT_ALREADY_ACTIVE", "The tool result already has an active verification attempt.", request);
    }

    @ExceptionHandler(VerificationToolResultInvalidException.class)
    public ResponseEntity<ErrorResponse> handleVerificationToolResultInvalid(VerificationToolResultInvalidException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "VERIFICATION_TOOL_RESULT_INVALID", "The tool result does not exist or does not belong to this ticket.", request);
    }

    /** SPEC-TW-025 acceptance-criteria: "Missing evidence returns 409 VERIFICATION_REQUIRED" (also covers old/stale workflow-cycle-attempt evidence, which is rejected identically). */
    @ExceptionHandler(VerificationEvidenceRequiredException.class)
    public ResponseEntity<ErrorResponse> handleVerificationEvidenceRequired(VerificationEvidenceRequiredException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "VERIFICATION_REQUIRED", "No trusted, current, successful verification evidence was found for this ticket.", request);
    }

    /** SPEC-TW-027 domain-rules: "the scheduler signal is advisory; the service recomputes eligibility under lock." */
    @ExceptionHandler(AutoCloseNotYetDueException.class)
    public ResponseEntity<ErrorResponse> handleAutoCloseNotYetDue(AutoCloseNotYetDueException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "AUTO_CLOSE_NOT_YET_DUE", "The ticket's auto-close due date has not yet passed.", request);
    }

    /** SPEC-TW-037 api-contract §"Errors": {@code 409} for a case already open for this ticket and source reference. */
    @ExceptionHandler(ReconciliationCaseConflictException.class)
    public ResponseEntity<ErrorResponse> handleReconciliationCaseConflict(ReconciliationCaseConflictException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "RECONCILIATION_CASE_CONFLICT", "A reconciliation case is already open for this ticket and source reference.", request);
    }

    /** SPEC-TW-038 api-contract §"Errors": {@code 404} — {@code sourceReference} does not match any known original event. */
    @ExceptionHandler(ReplaySourceEventNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleReplaySourceEventNotFound(ReplaySourceEventNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "REPLAY_SOURCE_EVENT_NOT_FOUND", "The original event referenced by sourceReference was not found.", request);
    }

    /** SPEC-TW-038 api-contract §"Errors": {@code 409} for a replay already open for this source reference. */
    @ExceptionHandler(ReplayEventConflictException.class)
    public ResponseEntity<ErrorResponse> handleReplayEventConflict(ReplayEventConflictException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "REPLAY_EVENT_CONFLICT", "A replay attempt is already open for this source reference.", request);
    }

    /** SPEC-TW-039 api-contract §"Errors": {@code 409} for a correction already open for this source reference. */
    @ExceptionHandler(CorrectionEventConflictException.class)
    public ResponseEntity<ErrorResponse> handleCorrectionEventConflict(CorrectionEventConflictException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "CORRECTION_EVENT_CONFLICT", "A correction event is already open for this ticket and source reference.", request);
    }

    /** SPEC-TW-040 api-contract §"Errors": {@code 409} for a compensation already open for this source reference. */
    @ExceptionHandler(CompensationConflictException.class)
    public ResponseEntity<ErrorResponse> handleCompensationConflict(CompensationConflictException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "COMPENSATION_CONFLICT", "A compensation is already open for this ticket and source reference.", request);
    }

    /** SPEC-TW-041 api-contract §"Errors": {@code 404} — {@code sourceReference} does not match any known reconciliation case. */
    @ExceptionHandler(IntegrityRepairSourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleIntegrityRepairSourceNotFound(IntegrityRepairSourceNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "INTEGRITY_REPAIR_SOURCE_NOT_FOUND", "The reconciliation case referenced by sourceReference was not found.", request);
    }

    /** SPEC-TW-041 api-contract §"Errors": {@code 409} for a repair already open for this source reference. */
    @ExceptionHandler(DataIntegrityRepairConflictException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityRepairConflict(DataIntegrityRepairConflictException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "DATA_INTEGRITY_REPAIR_CONFLICT", "A data integrity repair is already open for this source reference.", request);
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

    @ExceptionHandler({
        DisplayIdGenerationExhaustedException.class,
        EventSchemaValidationException.class,
        SensitiveReadAuditFailureException.class,
        SupportQueueAuthorizationFailClosedException.class,
        SecretDetectionFailClosedException.class,
        StepUpAuthenticationFailClosedException.class
    })
    public ResponseEntity<ErrorResponse> handleInternalFailure(RuntimeException ex, HttpServletRequest request) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred.", request);
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    public ResponseEntity<ErrorResponse> handleDependencyUnavailable(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "A required dependency is unavailable.", request);
    }

    /**
     * Logs server-side so operators retain visibility into unexpected
     * failures, while the response body stays generic — no stack trace,
     * SQL, or exception class name reaches the client.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("unexpected error handling {} {}", request.getMethod(), request.getRequestURI(), ex);
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

    private ErrorResponse bodyOfWithDetails(String code, String message, HttpServletRequest request, Map<String, Object> details) {
        String traceId = MDC.get("traceId");
        String correlationId = request.getHeader("X-Correlation-Id");
        return ErrorResponse.of(code, message, traceId == null ? "" : traceId, correlationId == null ? "" : correlationId, details);
    }
}
