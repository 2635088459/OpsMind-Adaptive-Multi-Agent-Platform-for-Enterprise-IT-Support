package dev.opsmind.ticketworkflow.ticket.application.policy;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Pure actor/operation eligibility rules for SPEC-TW-033 Support Queue
 * Authorization (domain-rules: "Any queue-scoped actor can only read or
 * mutate Tickets inside their authorized Support Queue scope"). Mirrors
 * {@link TicketViewPolicy} and {@link TicketTimelineViewPolicy}: queue-scoped
 * actor types are exactly the three Support roles those policies already
 * resolve to {@code SUPPORT_VIEW} — {@code EMPLOYEE} owns Tickets by
 * requester identity, not by queue, and {@code AUDITOR} reads across queues
 * under its own read-only policy, so neither is a Support Queue authorization
 * subject here. Contains no I/O: queue membership itself is resolved by
 * {@link dev.opsmind.ticketworkflow.ticket.application.port.out.SupportQueueMembershipPort}
 * in the caller.
 */
@Component
public class SupportQueueAuthorizationPolicy {

    public static final Set<String> QUEUE_SCOPED_ACTOR_TYPES = Set.of("IT_SUPPORT", "IT_ADMIN", "IT_MANAGER");

    /** SPEC-TW-033 README §1: "reads, queue queries, and command admission". */
    public static final Set<String> RECOGNIZED_OPERATIONS = Set.of("ticket.read", "ticket.query", "ticket.command");

    public boolean isRecognizedOperation(String operation) {
        return operation != null && RECOGNIZED_OPERATIONS.contains(operation);
    }

    public boolean isQueueScopedActorType(String actorType) {
        return actorType != null && QUEUE_SCOPED_ACTOR_TYPES.contains(actorType);
    }
}
