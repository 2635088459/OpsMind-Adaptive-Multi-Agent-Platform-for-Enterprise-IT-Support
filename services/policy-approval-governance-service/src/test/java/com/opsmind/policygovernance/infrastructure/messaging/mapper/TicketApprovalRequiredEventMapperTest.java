package com.opsmind.policygovernance.infrastructure.messaging.mapper;

import com.opsmind.policygovernance.application.command.RequestApprovalCommand;
import com.opsmind.policygovernance.domain.approval.ApprovalType;
import com.opsmind.policygovernance.domain.decision.Constraint;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import com.opsmind.policygovernance.infrastructure.messaging.consumer.ConsumedEventSchemaInvalidException;
import com.opsmind.policygovernance.infrastructure.messaging.contract.ConsumedEventEnvelope;
import com.opsmind.policygovernance.infrastructure.messaging.contract.TicketApprovalRequiredPayload;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-PG-027: pure mapping unit tests — no Spring, no RabbitMQ. Mirrors {@code ToolApprovalRequiredEventMapperTest}. */
@Tag("unit")
class TicketApprovalRequiredEventMapperTest {

    private ConsumedEventEnvelope envelope(Map<String, Object> payloadMap) {
        return new ConsumedEventEnvelope(
            "evt-1", "ticket.approval.required.v1", "ticket-workflow-service", 1,
            "ticket-1", "ticket-1", "corr-1", "cause-0", Instant.parse("2026-08-23T00:00:00Z"), payloadMap
        );
    }

    private TicketApprovalRequiredPayload payloadWithException(String exceptionType) {
        return new TicketApprovalRequiredPayload(
            "ticket-1", exceptionType, "HIGH", "hash-1",
            List.of(new Constraint(Constraint.Type.READ_ONLY, "read-only")), Instant.now().plusSeconds(3600)
        );
    }

    @Test
    void mapsEveryFieldFromTheEnvelopeAndPayload() {
        RequestApprovalCommand command = TicketApprovalRequiredEventMapper.toCommand(envelope(Map.of()), payloadWithException(null));

        assertThat(command.requestKey()).isEqualTo("ticket-1");
        assertThat(command.requestHash()).isEqualTo("hash-1");
        assertThat(command.sourceDomain()).isEqualTo("ticket-workflow-service");
        assertThat(command.sourceRequestId()).isEqualTo("ticket-1");
        assertThat(command.ticketId()).isEqualTo("ticket-1");
        assertThat(command.workflowInstanceId()).isNull();
        assertThat(command.toolRequestId()).isNull();
        assertThat(command.requestedBy()).isEqualTo("ticket-workflow-service");
        assertThat(command.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(command.constraints()).hasSize(1);
        assertThat(command.expiresAt()).isNotNull();
        assertThat(command.correlationId()).isEqualTo("corr-1");
        assertThat(command.causationId()).isEqualTo("evt-1");
    }

    /** SPEC-PG-023's three ticket-exception ApprovalType values get their first real caller here. */
    @Test
    void nullExceptionTypeMapsToTheGenericTicketAction() {
        RequestApprovalCommand command = TicketApprovalRequiredEventMapper.toCommand(envelope(Map.of()), payloadWithException(null));
        assertThat(command.approvalType()).isEqualTo(ApprovalType.TICKET_ACTION);
    }

    @Test
    void slaExceptionMapsToTicketSlaException() {
        RequestApprovalCommand command = TicketApprovalRequiredEventMapper.toCommand(envelope(Map.of()), payloadWithException("SLA_EXCEPTION"));
        assertThat(command.approvalType()).isEqualTo(ApprovalType.TICKET_SLA_EXCEPTION);
    }

    @Test
    void closureOverrideMapsToTicketClosureOverride() {
        RequestApprovalCommand command = TicketApprovalRequiredEventMapper.toCommand(envelope(Map.of()), payloadWithException("CLOSURE_OVERRIDE"));
        assertThat(command.approvalType()).isEqualTo(ApprovalType.TICKET_CLOSURE_OVERRIDE);
    }

    @Test
    void escalationExceptionMapsToTicketEscalationException() {
        RequestApprovalCommand command = TicketApprovalRequiredEventMapper.toCommand(envelope(Map.of()), payloadWithException("ESCALATION_EXCEPTION"));
        assertThat(command.approvalType()).isEqualTo(ApprovalType.TICKET_ESCALATION_EXCEPTION);
    }

    /** An unrecognized (non-null) exceptionType is a schema error, not a silent miscategorization. */
    @Test
    void anUnrecognizedExceptionTypeIsRejected() {
        assertThatThrownBy(() -> TicketApprovalRequiredEventMapper.toCommand(envelope(Map.of()), payloadWithException("SOMETHING_ELSE")))
            .isInstanceOf(ConsumedEventSchemaInvalidException.class);
    }

    @Test
    void ticketIdFallsBackToTheEnvelopeWhenThePayloadOmitsIt() {
        TicketApprovalRequiredPayload payloadWithoutTicketId = new TicketApprovalRequiredPayload(null, null, "HIGH", "hash-1", List.of(), null);

        RequestApprovalCommand command = TicketApprovalRequiredEventMapper.toCommand(envelope(Map.of()), payloadWithoutTicketId);

        assertThat(command.ticketId()).isEqualTo("ticket-1");
    }

    @Test
    void rejectsAMissingInputHash() {
        TicketApprovalRequiredPayload payload = new TicketApprovalRequiredPayload("ticket-1", null, "HIGH", null, List.of(), null);

        assertThatThrownBy(() -> TicketApprovalRequiredEventMapper.toCommand(envelope(Map.of()), payload))
            .isInstanceOf(ConsumedEventSchemaInvalidException.class);
    }

    @Test
    void rejectsAnUnrecognizedRiskLevel() {
        TicketApprovalRequiredPayload payload = new TicketApprovalRequiredPayload("ticket-1", null, "SUPER_HIGH", "hash-1", List.of(), null);

        assertThatThrownBy(() -> TicketApprovalRequiredEventMapper.toCommand(envelope(Map.of()), payload))
            .isInstanceOf(ConsumedEventSchemaInvalidException.class);
    }
}
