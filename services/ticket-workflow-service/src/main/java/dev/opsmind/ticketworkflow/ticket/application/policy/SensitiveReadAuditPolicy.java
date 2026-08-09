package dev.opsmind.ticketworkflow.ticket.application.policy;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Pure actor/operation eligibility rules for SPEC-TW-034 Sensitive Read
 * Audit. {@code RECOGNIZED_OPERATIONS} is exactly the two existing
 * sensitive-read views that already produce a required, fail-closed {@code
 * ticket.audit_records} entry — Get Ticket's {@code SUPPORT_VIEW} (SPEC-TW-002
 * §16) and Ticket Timeline's {@code SUPPORT_INTERNAL_VIEW} (SPEC-TW-006
 * §23) — and {@code READ_ELIGIBLE_ACTOR_TYPES} mirrors {@link
 * TicketViewPolicy}/{@link TicketTimelineViewPolicy}'s full actor-type
 * vocabulary, since an actor outside that set can never reach either read
 * path in the first place. Contains no I/O: the actual audit write is
 * performed by the caller through {@code SensitiveReadAuditPort} (business
 * trail) and/or {@code SensitiveReadAuditDecisionPort} (policy trail).
 */
@Component
public class SensitiveReadAuditPolicy {

    /** Get Ticket's {@code SUPPORT_VIEW} and Ticket Timeline's {@code SUPPORT_INTERNAL_VIEW}, expressed as this SPEC's operation vocabulary. */
    public static final Set<String> RECOGNIZED_OPERATIONS = Set.of("ticket.read", "ticket.timeline.read");

    public static final Set<String> READ_ELIGIBLE_ACTOR_TYPES = Set.of("EMPLOYEE", "IT_SUPPORT", "IT_ADMIN", "IT_MANAGER", "AUDITOR");

    public boolean isRecognizedOperation(String operation) {
        return operation != null && RECOGNIZED_OPERATIONS.contains(operation);
    }

    public boolean isReadEligibleActorType(String actorType) {
        return actorType != null && READ_ELIGIBLE_ACTOR_TYPES.contains(actorType);
    }
}
