package dev.opsmind.ticketworkflow.ticket.application.observability;

import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineViewType;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketViewType;
import dev.opsmind.ticketworkflow.ticket.domain.message.MessageVisibility;
import dev.opsmind.ticketworkflow.ticket.domain.message.TicketMessageType;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketPriority;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Central place for Create Ticket metric names so they are not scattered
 * across application code (13-package-and-class-design §59). Labels stay
 * low-cardinality: no ticketId, requesterId, eventId, or idempotencyKey.
 */
@Component
public class TicketTelemetry {

    private final MeterRegistry meterRegistry;

    public TicketTelemetry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordCreated(ApplicationCode applicationCode, TicketSource source) {
        Counter.builder("opsmind_ticket_created_total")
            .tag("application_code", applicationCode.name())
            .tag("source", source.name())
            .register(meterRegistry)
            .increment();
    }

    public void recordIdempotencyReplay() {
        Counter.builder("opsmind_ticket_idempotency_replay_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordAuthorizationDenied(String operation) {
        Counter.builder("opsmind_ticket_authorization_denied_total")
            .tag("operation", operation)
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startGetTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopGetTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_ticket_get_duration_seconds").register(meterRegistry));
    }

    public void recordGet(TicketViewType viewType) {
        Counter.builder("opsmind_ticket_get_total")
            .tag("view_type", viewType.name())
            .register(meterRegistry)
            .increment();
    }

    public void recordGetNotFound() {
        Counter.builder("opsmind_ticket_get_not_found_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordGetAuthorizationDenied() {
        Counter.builder("opsmind_ticket_get_authorization_denied_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordGetNotModified() {
        Counter.builder("opsmind_ticket_get_not_modified_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordSensitiveReadAuditFailure() {
        Counter.builder("opsmind_ticket_sensitive_read_audit_failure_total")
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startListTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopListTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_ticket_list_duration_seconds").register(meterRegistry));
    }

    public void recordList(boolean cursorPresent, boolean hasFilters, int resultCount) {
        Counter.builder("opsmind_ticket_list_total")
            .tag("cursor_present", String.valueOf(cursorPresent))
            .tag("has_filters", String.valueOf(hasFilters))
            .register(meterRegistry)
            .increment();
        DistributionSummary.builder("opsmind_ticket_list_result_count")
            .register(meterRegistry)
            .record(resultCount);
    }

    public void recordListInvalidCursor() {
        Counter.builder("opsmind_ticket_list_invalid_cursor_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordListAuthorizationDenied() {
        Counter.builder("opsmind_ticket_list_authorization_denied_total")
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startMessageAddTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopMessageAddTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_ticket_message_add_duration_seconds").register(meterRegistry));
    }

    public void recordMessageAdd(String actorType, TicketMessageType messageType, MessageVisibility visibility) {
        Counter.builder("opsmind_ticket_message_add_total")
            .tag("actor_type", actorType)
            .tag("message_type", messageType.name())
            .tag("visibility", visibility.name())
            .register(meterRegistry)
            .increment();
    }

    public void recordMessageReplay() {
        Counter.builder("opsmind_ticket_message_replay_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordMessageStateRejected() {
        Counter.builder("opsmind_ticket_message_state_rejected_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordMessageAuthorizationDenied() {
        Counter.builder("opsmind_ticket_message_authorization_denied_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordMessageSecretRejected() {
        Counter.builder("opsmind_ticket_message_secret_rejected_total")
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startSupportQueueTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopSupportQueueTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_support_queue_duration_seconds").register(meterRegistry));
    }

    public void recordSupportQueueList(boolean cursorPresent, int resultCount) {
        Counter.builder("opsmind_support_queue_list_total")
            .tag("cursor_present", String.valueOf(cursorPresent))
            .register(meterRegistry)
            .increment();
        DistributionSummary.builder("opsmind_support_queue_result_count")
            .register(meterRegistry)
            .record(resultCount);
    }

    public void recordSupportQueueInvalidCursor() {
        Counter.builder("opsmind_support_queue_invalid_cursor_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordSupportQueueAuthorizationDenied() {
        Counter.builder("opsmind_support_queue_authorization_denied_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordSupportQueueFilterOutsideScope() {
        Counter.builder("opsmind_support_queue_filter_outside_scope_total")
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startTimelineTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopTimelineTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_ticket_timeline_duration_seconds").register(meterRegistry));
    }

    public void recordTimeline(TicketTimelineViewType viewType, boolean cursorPresent, int resultCount) {
        Counter.builder("opsmind_ticket_timeline_total")
            .tag("view_type", viewType.name())
            .tag("cursor_present", String.valueOf(cursorPresent))
            .register(meterRegistry)
            .increment();
        DistributionSummary.builder("opsmind_ticket_timeline_result_count")
            .register(meterRegistry)
            .record(resultCount);
    }

    public void recordTimelineNotFound() {
        Counter.builder("opsmind_ticket_timeline_not_found_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordTimelineAuthorizationDenied() {
        Counter.builder("opsmind_ticket_timeline_authorization_denied_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordTimelineInvalidCursor() {
        Counter.builder("opsmind_ticket_timeline_invalid_cursor_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordTimelineSensitiveReadAuditFailure() {
        Counter.builder("opsmind_ticket_timeline_sensitive_read_audit_failure_total")
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startTriageTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopTriageTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_ticket_triage_duration_seconds").register(meterRegistry));
    }

    public void recordTriaged(TicketPriority priority) {
        Counter.builder("opsmind_ticket_triage_total")
            .tag("priority", priority.name())
            .register(meterRegistry)
            .increment();
    }

    public void recordTriageReplay() {
        Counter.builder("opsmind_ticket_triage_replay_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordTriageAuthorizationDenied(String reason) {
        Counter.builder("opsmind_ticket_triage_authorization_denied_total")
            .tag("reason", reason)
            .register(meterRegistry)
            .increment();
    }

    public void recordTriageStateRejected() {
        Counter.builder("opsmind_ticket_triage_state_rejected_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordTriageVersionConflict() {
        Counter.builder("opsmind_ticket_triage_version_conflict_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordTriageCatalogInvalid(String catalogType) {
        Counter.builder("opsmind_ticket_triage_catalog_invalid_total")
            .tag("catalog_type", catalogType)
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startAssignmentTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopAssignmentTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_ticket_assignment_duration_seconds").register(meterRegistry));
    }

    public void recordAssignmentCommand(String operation, String outcome) {
        Counter.builder("opsmind_ticket_assignment_commands_total")
            .tag("operation", operation)
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    public void recordAssignmentAuthorizationDenied() {
        Counter.builder("opsmind_ticket_assignment_authorization_denied_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordAssignmentConflict(String type) {
        Counter.builder("opsmind_ticket_assignment_conflicts_total")
            .tag("type", type)
            .register(meterRegistry)
            .increment();
    }

    public void recordAssignmentEligibilityDenied(String reason) {
        Counter.builder("opsmind_ticket_assignment_eligibility_denied_total")
            .tag("reason", reason)
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startStatusTransitionTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopStatusTransitionTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_ticket_status_transition_duration_seconds").register(meterRegistry));
    }

    public void recordStatusTransitionCommand(String transition, String outcome) {
        Counter.builder("opsmind_ticket_status_transition_commands_total")
            .tag("transition", transition)
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    public void recordStatusTransitionAuthorizationDenied() {
        Counter.builder("opsmind_ticket_status_transition_authorization_denied_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordStatusTransitionConflict(String type) {
        Counter.builder("opsmind_ticket_status_transition_conflicts_total")
            .tag("type", type)
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startResolutionTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopResolutionTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_ticket_resolution_duration_seconds").register(meterRegistry));
    }

    public void recordResolutionCommand(String resolutionCode, String outcome) {
        Counter.builder("opsmind_ticket_resolution_commands_total")
            .tag("resolution_code", resolutionCode)
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    public void recordResolutionAuthorizationDenied() {
        Counter.builder("opsmind_ticket_resolution_authorization_denied_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordResolutionConflict(String type) {
        Counter.builder("opsmind_ticket_resolution_conflicts_total")
            .tag("type", type)
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startCloseTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopCloseTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_ticket_close_duration_seconds").register(meterRegistry));
    }

    public void recordCloseCommand(String outcome) {
        Counter.builder("opsmind_ticket_close_commands_total")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    public void recordCloseAuthorizationDenied() {
        Counter.builder("opsmind_ticket_close_authorization_denied_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordCloseConflict(String type) {
        Counter.builder("opsmind_ticket_close_conflicts_total")
            .tag("type", type)
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startReopenTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopReopenTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_ticket_reopen_duration_seconds").register(meterRegistry));
    }

    public void recordReopenCommand(String outcome) {
        Counter.builder("opsmind_ticket_reopen_commands_total")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    public void recordReopenAuthorizationDenied() {
        Counter.builder("opsmind_ticket_reopen_authorization_denied_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordReopenConflict(String type) {
        Counter.builder("opsmind_ticket_reopen_conflicts_total")
            .tag("type", type)
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startRequestUserInputTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopRequestUserInputTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_ticket_request_user_input_duration_seconds").register(meterRegistry));
    }

    public void recordRequestUserInputCommand(String outcome) {
        Counter.builder("opsmind_ticket_request_user_input_commands_total")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    public void recordRequestUserInputAuthorizationDenied() {
        Counter.builder("opsmind_ticket_request_user_input_authorization_denied_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordRequestUserInputConflict(String type) {
        Counter.builder("opsmind_ticket_request_user_input_conflicts_total")
            .tag("type", type)
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startUserReplyTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopUserReplyTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_ticket_user_reply_duration_seconds").register(meterRegistry));
    }

    public void recordUserReplyCommand(String outcome) {
        Counter.builder("opsmind_ticket_user_reply_commands_total")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    public void recordUserReplyResumeApplied(boolean resumeApplied) {
        Counter.builder("opsmind_ticket_user_reply_resume_applied_total")
            .tag("resume_applied", String.valueOf(resumeApplied))
            .register(meterRegistry)
            .increment();
    }

    public void recordUserReplyAuthorizationDenied() {
        Counter.builder("opsmind_ticket_user_reply_authorization_denied_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordUserReplyConflict(String type) {
        Counter.builder("opsmind_ticket_user_reply_conflicts_total")
            .tag("type", type)
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startRequestApprovalTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopRequestApprovalTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_ticket_request_approval_duration_seconds").register(meterRegistry));
    }

    public void recordRequestApprovalCommand(String outcome) {
        Counter.builder("opsmind_ticket_request_approval_commands_total")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    public void recordRequestApprovalAuthorizationDenied() {
        Counter.builder("opsmind_ticket_request_approval_authorization_denied_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordRequestApprovalConflict(String type) {
        Counter.builder("opsmind_ticket_request_approval_conflicts_total")
            .tag("type", type)
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startApplyApprovalGrantedTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopApplyApprovalGrantedTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_ticket_apply_approval_granted_duration_seconds").register(meterRegistry));
    }

    public void recordApplyApprovalGrantedOutcome(String outcome) {
        Counter.builder("opsmind_ticket_apply_approval_granted_outcomes_total")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    public void recordApprovalGrantedConsumed() {
        Counter.builder("opsmind_ticket_event_consumed_total")
            .tag("event_type", "approval.granted")
            .register(meterRegistry)
            .increment();
    }

    public void recordApprovalGrantedDlq(String reason) {
        Counter.builder("opsmind_ticket_event_dlq_total")
            .tag("event_type", "approval.granted")
            .tag("reason", reason)
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startApplyApprovalRejectedTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopApplyApprovalRejectedTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_ticket_apply_approval_rejected_duration_seconds").register(meterRegistry));
    }

    public void recordApplyApprovalRejectedOutcome(String outcome) {
        Counter.builder("opsmind_ticket_apply_approval_rejected_outcomes_total")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    public void recordApprovalRejectedConsumed() {
        Counter.builder("opsmind_ticket_event_consumed_total")
            .tag("event_type", "approval.rejected")
            .register(meterRegistry)
            .increment();
    }

    public void recordApprovalRejectedDlq(String reason) {
        Counter.builder("opsmind_ticket_event_dlq_total")
            .tag("event_type", "approval.rejected")
            .tag("reason", reason)
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startApplyApprovalExpiredTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopApplyApprovalExpiredTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_ticket_apply_approval_expired_duration_seconds").register(meterRegistry));
    }

    public void recordApplyApprovalExpiredOutcome(String outcome) {
        Counter.builder("opsmind_ticket_apply_approval_expired_outcomes_total")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    public void recordApprovalExpiredConsumed() {
        Counter.builder("opsmind_ticket_event_consumed_total")
            .tag("event_type", "approval.expired")
            .register(meterRegistry)
            .increment();
    }

    public void recordApprovalExpiredDlq(String reason) {
        Counter.builder("opsmind_ticket_event_dlq_total")
            .tag("event_type", "approval.expired")
            .tag("reason", reason)
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startApplyAutoApprovedPolicyTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopApplyAutoApprovedPolicyTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_ticket_apply_auto_approved_policy_duration_seconds").register(meterRegistry));
    }

    public void recordApplyAutoApprovedPolicyOutcome(String outcome) {
        Counter.builder("opsmind_ticket_apply_auto_approved_policy_outcomes_total")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    public void recordPolicyActionAutoApprovedConsumed() {
        Counter.builder("opsmind_ticket_event_consumed_total")
            .tag("event_type", "policy.action_auto_approved")
            .register(meterRegistry)
            .increment();
    }

    public void recordPolicyActionAutoApprovedDlq(String reason) {
        Counter.builder("opsmind_ticket_event_dlq_total")
            .tag("event_type", "policy.action_auto_approved")
            .tag("reason", reason)
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startApplyToolExecutionCompletedTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopApplyToolExecutionCompletedTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_ticket_apply_tool_execution_completed_duration_seconds").register(meterRegistry));
    }

    public void recordApplyToolExecutionCompletedOutcome(String outcome) {
        Counter.builder("opsmind_ticket_apply_tool_execution_completed_outcomes_total")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    public void recordToolExecutionCompletedConsumed() {
        Counter.builder("opsmind_ticket_event_consumed_total")
            .tag("event_type", "tool.execution.completed")
            .register(meterRegistry)
            .increment();
    }

    public void recordToolExecutionCompletedDlq(String reason) {
        Counter.builder("opsmind_ticket_event_dlq_total")
            .tag("event_type", "tool.execution.completed")
            .tag("reason", reason)
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startApplyToolExecutionFailedTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopApplyToolExecutionFailedTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_ticket_apply_tool_execution_failed_duration_seconds").register(meterRegistry));
    }

    public void recordApplyToolExecutionFailedOutcome(String outcome) {
        Counter.builder("opsmind_ticket_apply_tool_execution_failed_outcomes_total")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    public void recordToolExecutionFailedConsumed() {
        Counter.builder("opsmind_ticket_event_consumed_total")
            .tag("event_type", "tool.execution.failed")
            .register(meterRegistry)
            .increment();
    }

    public void recordToolExecutionFailedDlq(String reason) {
        Counter.builder("opsmind_ticket_event_dlq_total")
            .tag("event_type", "tool.execution.failed")
            .tag("reason", reason)
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startApplyToolResultUnknownTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopApplyToolResultUnknownTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_ticket_apply_tool_result_unknown_duration_seconds").register(meterRegistry));
    }

    public void recordApplyToolResultUnknownOutcome(String outcome) {
        Counter.builder("opsmind_ticket_apply_tool_result_unknown_outcomes_total")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    public void recordToolResultUnknownConsumed() {
        Counter.builder("opsmind_ticket_event_consumed_total")
            .tag("event_type", "tool.execution.result_unknown")
            .register(meterRegistry)
            .increment();
    }

    public void recordToolResultUnknownDlq(String reason) {
        Counter.builder("opsmind_ticket_event_dlq_total")
            .tag("event_type", "tool.execution.result_unknown")
            .tag("reason", reason)
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startStartVerificationTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopStartVerificationTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_ticket_start_verification_duration_seconds").register(meterRegistry));
    }

    public void recordStartVerificationCommand(String outcome) {
        Counter.builder("opsmind_ticket_start_verification_commands_total")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    public void recordStartVerificationAuthorizationDenied() {
        Counter.builder("opsmind_ticket_start_verification_authorization_denied_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordStartVerificationConflict(String type) {
        Counter.builder("opsmind_ticket_start_verification_conflicts_total")
            .tag("type", type)
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startApplyVerificationSuccessTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopApplyVerificationSuccessTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_ticket_apply_verification_success_duration_seconds").register(meterRegistry));
    }

    public void recordApplyVerificationSuccessOutcome(String outcome) {
        Counter.builder("opsmind_ticket_apply_verification_success_outcomes_total")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    public void recordVerificationCompletedConsumed() {
        Counter.builder("opsmind_ticket_event_consumed_total")
            .tag("event_type", "verification.completed")
            .register(meterRegistry)
            .increment();
    }

    public void recordVerificationCompletedDlq(String reason) {
        Counter.builder("opsmind_ticket_event_dlq_total")
            .tag("event_type", "verification.completed")
            .tag("reason", reason)
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startApplyVerificationFailureTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopApplyVerificationFailureTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_ticket_apply_verification_failure_duration_seconds").register(meterRegistry));
    }

    public void recordApplyVerificationFailureOutcome(String outcome) {
        Counter.builder("opsmind_ticket_apply_verification_failure_outcomes_total")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    public void recordVerificationFailedConsumed() {
        Counter.builder("opsmind_ticket_event_consumed_total")
            .tag("event_type", "verification.failed")
            .register(meterRegistry)
            .increment();
    }

    public void recordVerificationFailedDlq(String reason) {
        Counter.builder("opsmind_ticket_event_dlq_total")
            .tag("event_type", "verification.failed")
            .tag("reason", reason)
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startResolveWithVerificationTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopResolveWithVerificationTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_ticket_resolve_with_verification_duration_seconds").register(meterRegistry));
    }

    public void recordResolveWithVerificationCommand(String outcome) {
        Counter.builder("opsmind_ticket_resolve_with_verification_commands_total")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    public void recordResolveWithVerificationAuthorizationDenied() {
        Counter.builder("opsmind_ticket_resolve_with_verification_authorization_denied_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordResolveWithVerificationConflict(String type) {
        Counter.builder("opsmind_ticket_resolve_with_verification_conflicts_total")
            .tag("type", type)
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startConfirmResolutionTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopConfirmResolutionTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_ticket_confirm_resolution_duration_seconds").register(meterRegistry));
    }

    public void recordConfirmResolutionCommand(String outcome) {
        Counter.builder("opsmind_ticket_confirm_resolution_commands_total")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    public void recordConfirmResolutionAuthorizationDenied() {
        Counter.builder("opsmind_ticket_confirm_resolution_authorization_denied_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordConfirmResolutionConflict(String type) {
        Counter.builder("opsmind_ticket_confirm_resolution_conflicts_total")
            .tag("type", type)
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startAutoCloseTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopAutoCloseTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_ticket_auto_close_duration_seconds").register(meterRegistry));
    }

    public void recordAutoCloseCommand(String outcome) {
        Counter.builder("opsmind_ticket_auto_close_commands_total")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    public void recordAutoCloseAuthorizationDenied() {
        Counter.builder("opsmind_ticket_auto_close_authorization_denied_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordAutoCloseConflict(String type) {
        Counter.builder("opsmind_ticket_auto_close_conflicts_total")
            .tag("type", type)
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startRequesterReopenTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopRequesterReopenTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_ticket_requester_reopen_duration_seconds").register(meterRegistry));
    }

    public void recordRequesterReopenCommand(String outcome) {
        Counter.builder("opsmind_ticket_requester_reopen_commands_total")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    public void recordRequesterReopenAuthorizationDenied() {
        Counter.builder("opsmind_ticket_requester_reopen_authorization_denied_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordRequesterReopenConflict(String type) {
        Counter.builder("opsmind_ticket_requester_reopen_conflicts_total")
            .tag("type", type)
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startCancelTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopCancelTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_ticket_cancel_duration_seconds").register(meterRegistry));
    }

    public void recordCancelCommand(String outcome) {
        Counter.builder("opsmind_ticket_cancel_commands_total")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    public void recordCancelAuthorizationDenied() {
        Counter.builder("opsmind_ticket_cancel_authorization_denied_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordCancelConflict(String type) {
        Counter.builder("opsmind_ticket_cancel_conflicts_total")
            .tag("type", type)
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startAssignmentRouteTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopAssignmentRouteTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_ticket_assignment_route_duration_seconds").register(meterRegistry));
    }

    public void recordAssignmentRouteCommand(String outcome) {
        Counter.builder("opsmind_ticket_assignment_route_commands_total")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    public void recordAssignmentRouteAuthorizationDenied() {
        Counter.builder("opsmind_ticket_assignment_route_authorization_denied_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordAssignmentRouteConflict(String type) {
        Counter.builder("opsmind_ticket_assignment_route_conflicts_total")
            .tag("type", type)
            .register(meterRegistry)
            .increment();
    }

    public void recordAssignmentRouteEligibilityDenied(String reason) {
        Counter.builder("opsmind_ticket_assignment_route_eligibility_denied_total")
            .tag("reason", reason)
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startEscalateTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopEscalateTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_ticket_escalate_duration_seconds").register(meterRegistry));
    }

    public void recordEscalateCommand(String outcome) {
        Counter.builder("opsmind_ticket_escalate_commands_total")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    public void recordEscalateAuthorizationDenied() {
        Counter.builder("opsmind_ticket_escalate_authorization_denied_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordEscalateConflict(String type) {
        Counter.builder("opsmind_ticket_escalate_conflicts_total")
            .tag("type", type)
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startEscalationResumeTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopEscalationResumeTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_ticket_escalation_resume_duration_seconds").register(meterRegistry));
    }

    public void recordEscalationResumeCommand(String outcome) {
        Counter.builder("opsmind_ticket_escalation_resume_commands_total")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    public void recordEscalationResumeAuthorizationDenied() {
        Counter.builder("opsmind_ticket_escalation_resume_authorization_denied_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordEscalationResumeConflict(String type) {
        Counter.builder("opsmind_ticket_escalation_resume_conflicts_total")
            .tag("type", type)
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startSupportQueueAuthorizationTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopSupportQueueAuthorizationTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_support_queue_authorization_duration_seconds").register(meterRegistry));
    }

    /** {@code decisionCode} is a fixed, small vocabulary (SPEC-TW-033) — safe as a low-cardinality tag. */
    public void recordSupportQueueAuthorizationDecision(String decisionCode) {
        Counter.builder("opsmind_support_queue_authorization_decisions_total")
            .tag("decision_code", decisionCode)
            .register(meterRegistry)
            .increment();
    }

    public void recordSupportQueueAuthorizationCallerDenied() {
        Counter.builder("opsmind_support_queue_authorization_caller_denied_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordSupportQueueAuthorizationFailClosed() {
        Counter.builder("opsmind_support_queue_authorization_fail_closed_total")
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startSensitiveReadAuditPolicyTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopSensitiveReadAuditPolicyTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_sensitive_read_audit_policy_duration_seconds").register(meterRegistry));
    }

    /** {@code decisionCode} is a fixed, small vocabulary (SPEC-TW-034) — safe as a low-cardinality tag. */
    public void recordSensitiveReadAuditPolicyDecision(String decisionCode) {
        Counter.builder("opsmind_sensitive_read_audit_policy_decisions_total")
            .tag("decision_code", decisionCode)
            .register(meterRegistry)
            .increment();
    }

    public void recordSensitiveReadAuditPolicyCallerDenied() {
        Counter.builder("opsmind_sensitive_read_audit_policy_caller_denied_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordSensitiveReadAuditPolicyFailClosed() {
        Counter.builder("opsmind_sensitive_read_audit_policy_fail_closed_total")
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startSecretDetectionPolicyTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopSecretDetectionPolicyTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_secret_detection_policy_duration_seconds").register(meterRegistry));
    }

    /** {@code decisionCode} is a fixed, small vocabulary (SPEC-TW-035) — safe as a low-cardinality tag; never the matched text. */
    public void recordSecretDetectionPolicyDecision(String decisionCode) {
        Counter.builder("opsmind_secret_detection_policy_decisions_total")
            .tag("decision_code", decisionCode)
            .register(meterRegistry)
            .increment();
    }

    public void recordSecretDetectionPolicyCallerDenied() {
        Counter.builder("opsmind_secret_detection_policy_caller_denied_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordSecretDetectionPolicyFailClosed() {
        Counter.builder("opsmind_secret_detection_policy_fail_closed_total")
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startStepUpAuthenticationPolicyTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopStepUpAuthenticationPolicyTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_step_up_authentication_policy_duration_seconds").register(meterRegistry));
    }

    /** {@code decisionCode} is a fixed, small vocabulary (SPEC-TW-036) — safe as a low-cardinality tag. */
    public void recordStepUpAuthenticationPolicyDecision(String decisionCode) {
        Counter.builder("opsmind_step_up_authentication_policy_decisions_total")
            .tag("decision_code", decisionCode)
            .register(meterRegistry)
            .increment();
    }

    public void recordStepUpAuthenticationPolicyCallerDenied() {
        Counter.builder("opsmind_step_up_authentication_policy_caller_denied_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordStepUpAuthenticationPolicyFailClosed() {
        Counter.builder("opsmind_step_up_authentication_policy_fail_closed_total")
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startOpenReconciliationCaseTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopOpenReconciliationCaseTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_open_reconciliation_case_duration_seconds").register(meterRegistry));
    }

    public void recordOpenReconciliationCaseCommand(String outcome) {
        Counter.builder("opsmind_open_reconciliation_case_commands_total")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    public void recordOpenReconciliationCaseAuthorizationDenied() {
        Counter.builder("opsmind_open_reconciliation_case_authorization_denied_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordOpenReconciliationCaseConflict() {
        Counter.builder("opsmind_open_reconciliation_case_conflicts_total")
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startReplayEventTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopReplayEventTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_replay_event_duration_seconds").register(meterRegistry));
    }

    public void recordReplayEventCommand(String outcome) {
        Counter.builder("opsmind_replay_event_commands_total")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    public void recordReplayEventAuthorizationDenied() {
        Counter.builder("opsmind_replay_event_authorization_denied_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordReplayEventConflict() {
        Counter.builder("opsmind_replay_event_conflicts_total")
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startPublishCorrectionEventTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopPublishCorrectionEventTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_publish_correction_event_duration_seconds").register(meterRegistry));
    }

    public void recordPublishCorrectionEventCommand(String outcome) {
        Counter.builder("opsmind_publish_correction_event_commands_total")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    public void recordPublishCorrectionEventAuthorizationDenied() {
        Counter.builder("opsmind_publish_correction_event_authorization_denied_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordPublishCorrectionEventConflict() {
        Counter.builder("opsmind_publish_correction_event_conflicts_total")
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startExecuteCompensationTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopExecuteCompensationTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_execute_compensation_duration_seconds").register(meterRegistry));
    }

    public void recordExecuteCompensationCommand(String outcome) {
        Counter.builder("opsmind_execute_compensation_commands_total")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    public void recordExecuteCompensationAuthorizationDenied() {
        Counter.builder("opsmind_execute_compensation_authorization_denied_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordExecuteCompensationConflict() {
        Counter.builder("opsmind_execute_compensation_conflicts_total")
            .register(meterRegistry)
            .increment();
    }

    public Timer.Sample startApplyDataIntegrityRepairTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopApplyDataIntegrityRepairTimer(Timer.Sample sample) {
        sample.stop(Timer.builder("opsmind_apply_data_integrity_repair_duration_seconds").register(meterRegistry));
    }

    public void recordApplyDataIntegrityRepairCommand(String outcome) {
        Counter.builder("opsmind_apply_data_integrity_repair_commands_total")
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment();
    }

    public void recordApplyDataIntegrityRepairAuthorizationDenied() {
        Counter.builder("opsmind_apply_data_integrity_repair_authorization_denied_total")
            .register(meterRegistry)
            .increment();
    }

    public void recordApplyDataIntegrityRepairConflict() {
        Counter.builder("opsmind_apply_data_integrity_repair_conflicts_total")
            .register(meterRegistry)
            .increment();
    }
}
