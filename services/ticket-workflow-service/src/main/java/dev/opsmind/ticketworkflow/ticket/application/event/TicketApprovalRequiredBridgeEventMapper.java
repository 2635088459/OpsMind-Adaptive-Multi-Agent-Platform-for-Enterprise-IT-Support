package dev.opsmind.ticketworkflow.ticket.application.event;

import dev.opsmind.ticketworkflow.ticket.application.idempotency.RequestHashCalculator;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.domain.event.TicketApprovalWaitStarted;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Project-level integration verification (2026-09-01): a real translation
 * bridge, not a rename or edit of either domain's own already-shipped
 * contract. ticket-workflow-service's own {@code
 * ticket.approval-wait-started.v1} (SPEC-TW-014, see {@link
 * TicketApprovalWaitStartedEventMapper}) and policy-approval-governance-
 * service's own consumed {@code ticket.approval.required.v1} (SPEC-PG-027,
 * {@code TicketApprovalRequiredEventConsumer}) are two independently
 * specified contracts for what both domains intend as the same real-world
 * fact -- "a ticket now needs approval" -- neither wrong on its own terms,
 * simply never reconciled against each other (found live during the
 * project's first-ever multi-service bring-up: governance's own {@code
 * approval_requests} table stayed empty even after the outbox dispatcher
 * itself was proven working).
 * <p>
 * This mapper stages a SECOND, additional outbox entry -- alongside {@link
 * TicketApprovalWaitStartedEventMapper}'s own, completely untouched one --
 * shaped to match governance's consumer contract exactly, rather than
 * editing either already-shipped contract. Both entries are staged in the
 * same transaction as the ticket's own {@code WAITING_FOR_APPROVAL}
 * transition (see {@code RequestApprovalApplicationService}), so either
 * both are durably queued or neither is.
 * <p>
 * {@code inputHash} reuses this service's own {@link RequestHashCalculator}
 * -- the same SHA-256-over-canonical-JSON utility already used for
 * idempotency hashing -- as a deterministic, one-way digest of exactly the
 * fields that identify "what is being approved"
 * (workflowId/actionId/actionType/riskLevel/riskContext). This is exactly
 * what governance's own {@code inputHash} field is for, without ever
 * putting the actual risk-context detail on the wire, matching {@link
 * TicketApprovalWaitStartedEventMapper}'s own redaction stance for the
 * identical reason (a hash is a fingerprint, not a disclosure).
 * <p>
 * {@code exceptionType}/{@code constraints}/{@code expiresAt} are always
 * {@code null}/empty/{@code null}: this bridge only ever carries an
 * ordinary ticket action (never one of governance's three named ticket-
 * exception types), which {@code TicketApprovalRequiredEventMapper#
 * resolveApprovalType} on the consuming side already maps to the generic
 * {@code TICKET_ACTION} approval type -- a legitimate case its own javadoc
 * documents, not a gap this bridge is papering over.
 */
@Component
public class TicketApprovalRequiredBridgeEventMapper {

    private static final String AGGREGATE_TYPE = "Ticket";
    private static final String DATA_CLASSIFICATION = "INTERNAL";
    private static final String EVENT_TYPE = "ticket.approval.required.v1";
    private static final String EVENT_VERSION = "1";
    private static final String ROUTING_KEY = "ticket.approval.required.v1";
    private static final String HASH_SCOPE = "ticket-approval-required-bridge";

    private final RequestHashCalculator requestHashCalculator;

    public TicketApprovalRequiredBridgeEventMapper(RequestHashCalculator requestHashCalculator) {
        this.requestHashCalculator = requestHashCalculator;
    }

    public OutboxEventEntry map(TicketApprovalWaitStarted event, String traceId, String correlationId, String causationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ticketId", event.ticketId().value().toString());
        payload.put("exceptionType", null);
        payload.put("riskLevel", event.riskLevel().name());
        payload.put("inputHash", computeInputHash(event));
        payload.put("constraints", List.of());
        payload.put("expiresAt", null);

        return new OutboxEventEntry(
            UUID.randomUUID(),
            UUID.randomUUID().toString(),
            EVENT_TYPE,
            EVENT_VERSION,
            ROUTING_KEY,
            AGGREGATE_TYPE,
            event.ticketId().toString(),
            event.aggregateVersion(),
            TicketId.of(event.ticketId().value()),
            event.workflowId(),
            traceId,
            correlationId,
            causationId,
            DATA_CLASSIFICATION,
            payload,
            event.occurredAt(),
            event.occurredAt()
        );
    }

    private String computeInputHash(TicketApprovalWaitStarted event) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("workflowId", event.workflowId());
        canonical.put("actionId", event.actionId());
        canonical.put("actionType", event.actionType());
        canonical.put("riskLevel", event.riskLevel().name());
        canonical.put("riskContext", event.riskContext());
        return requestHashCalculator.calculate(HASH_SCOPE, ROUTING_KEY, event.ticketId().toString(), canonical);
    }
}
