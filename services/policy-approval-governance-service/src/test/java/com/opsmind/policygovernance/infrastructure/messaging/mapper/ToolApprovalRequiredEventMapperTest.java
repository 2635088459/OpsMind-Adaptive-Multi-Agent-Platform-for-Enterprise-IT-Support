package com.opsmind.policygovernance.infrastructure.messaging.mapper;

import com.opsmind.policygovernance.application.command.RequestApprovalCommand;
import com.opsmind.policygovernance.domain.approval.ApprovalType;
import com.opsmind.policygovernance.domain.decision.Constraint;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import com.opsmind.policygovernance.infrastructure.messaging.consumer.ConsumedEventSchemaInvalidException;
import com.opsmind.policygovernance.infrastructure.messaging.contract.ConsumedEventEnvelope;
import com.opsmind.policygovernance.infrastructure.messaging.contract.ToolApprovalRequiredPayload;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-PG-025: pure mapping unit tests — no Spring, no RabbitMQ. */
@Tag("unit")
class ToolApprovalRequiredEventMapperTest {

    private ConsumedEventEnvelope envelope(Map<String, Object> payloadMap) {
        return new ConsumedEventEnvelope(
            "evt-1", "tool.approval.required.v1", "tool-integration-gateway-service", 1,
            "tool-req-1", "ticket-1", "corr-1", "cause-0", Instant.parse("2026-08-23T00:00:00Z"), payloadMap
        );
    }

    private ToolApprovalRequiredPayload fullPayload() {
        return new ToolApprovalRequiredPayload(
            "tool-req-1", "ticket-1", "wf-1", "HIGH", "hash-1",
            List.of(new Constraint(Constraint.Type.READ_ONLY, "read-only")), Instant.now().plusSeconds(3600)
        );
    }

    @Test
    void mapsEveryFieldFromTheEnvelopeAndPayload() {
        RequestApprovalCommand command = ToolApprovalRequiredEventMapper.toCommand(envelope(Map.of()), fullPayload());

        assertThat(command.requestKey()).isEqualTo("tool-req-1");
        assertThat(command.requestHash()).isEqualTo("hash-1");
        assertThat(command.sourceDomain()).isEqualTo("tool-integration-gateway-service");
        assertThat(command.sourceRequestId()).isEqualTo("tool-req-1");
        assertThat(command.ticketId()).isEqualTo("ticket-1");
        assertThat(command.workflowInstanceId()).isEqualTo("wf-1");
        assertThat(command.toolRequestId()).isEqualTo("tool-req-1");
        assertThat(command.requestedBy()).isEqualTo("tool-integration-gateway-service");
        assertThat(command.approvalType()).isEqualTo(ApprovalType.TOOL_EXECUTION);
        assertThat(command.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(command.constraints()).hasSize(1);
        assertThat(command.expiresAt()).isNotNull();
        assertThat(command.correlationId()).isEqualTo("corr-1");
        assertThat(command.causationId()).isEqualTo("evt-1");
    }

    /** ticketId falls back to the envelope's own top-level field when the payload omits it. */
    @Test
    void ticketIdFallsBackToTheEnvelopeWhenThePayloadOmitsIt() {
        ToolApprovalRequiredPayload payloadWithoutTicketId = new ToolApprovalRequiredPayload(
            "tool-req-1", null, "wf-1", "HIGH", "hash-1", List.of(), null
        );

        RequestApprovalCommand command = ToolApprovalRequiredEventMapper.toCommand(envelope(Map.of()), payloadWithoutTicketId);

        assertThat(command.ticketId()).isEqualTo("ticket-1");
    }

    /** expiresAt/constraints are not named "Key fields" for this event — nullable/empty is a valid, honest mapping, not an error. */
    @Test
    void expiresAtAndConstraintsAreOptional() {
        ToolApprovalRequiredPayload minimal = new ToolApprovalRequiredPayload("tool-req-1", "ticket-1", null, "HIGH", "hash-1", null, null);

        RequestApprovalCommand command = ToolApprovalRequiredEventMapper.toCommand(envelope(Map.of()), minimal);

        assertThat(command.expiresAt()).isNull();
        assertThat(command.constraints()).isEmpty();
    }

    @Test
    void rejectsAMissingToolRequestId() {
        ToolApprovalRequiredPayload payload = new ToolApprovalRequiredPayload(null, "ticket-1", null, "HIGH", "hash-1", List.of(), null);

        assertThatThrownBy(() -> ToolApprovalRequiredEventMapper.toCommand(envelope(Map.of()), payload))
            .isInstanceOf(ConsumedEventSchemaInvalidException.class);
    }

    @Test
    void rejectsAMissingInputHash() {
        ToolApprovalRequiredPayload payload = new ToolApprovalRequiredPayload("tool-req-1", "ticket-1", null, "HIGH", null, List.of(), null);

        assertThatThrownBy(() -> ToolApprovalRequiredEventMapper.toCommand(envelope(Map.of()), payload))
            .isInstanceOf(ConsumedEventSchemaInvalidException.class);
    }

    @Test
    void rejectsAnUnrecognizedRiskLevel() {
        ToolApprovalRequiredPayload payload = new ToolApprovalRequiredPayload("tool-req-1", "ticket-1", null, "SUPER_HIGH", "hash-1", List.of(), null);

        assertThatThrownBy(() -> ToolApprovalRequiredEventMapper.toCommand(envelope(Map.of()), payload))
            .isInstanceOf(ConsumedEventSchemaInvalidException.class);
    }
}
