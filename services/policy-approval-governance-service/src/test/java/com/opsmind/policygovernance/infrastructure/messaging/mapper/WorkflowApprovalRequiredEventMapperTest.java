package com.opsmind.policygovernance.infrastructure.messaging.mapper;

import com.opsmind.policygovernance.application.command.RequestApprovalCommand;
import com.opsmind.policygovernance.domain.approval.ApprovalType;
import com.opsmind.policygovernance.domain.decision.Constraint;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import com.opsmind.policygovernance.infrastructure.messaging.consumer.ConsumedEventSchemaInvalidException;
import com.opsmind.policygovernance.infrastructure.messaging.contract.ConsumedEventEnvelope;
import com.opsmind.policygovernance.infrastructure.messaging.contract.WorkflowApprovalRequiredPayload;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-PG-026: pure mapping unit tests — no Spring, no RabbitMQ. Mirrors {@code ToolApprovalRequiredEventMapperTest}. */
@Tag("unit")
class WorkflowApprovalRequiredEventMapperTest {

    private ConsumedEventEnvelope envelope(Map<String, Object> payloadMap) {
        return new ConsumedEventEnvelope(
            "evt-1", "workflow.approval.required.v1", "agent-runtime-service", 1,
            "wf-1", "ticket-1", "corr-1", "cause-0", Instant.parse("2026-08-23T00:00:00Z"), payloadMap
        );
    }

    private WorkflowApprovalRequiredPayload fullPayload() {
        return new WorkflowApprovalRequiredPayload(
            "wf-1", "ticket-1", "HIGH", "hash-1",
            List.of(new Constraint(Constraint.Type.READ_ONLY, "read-only")), Instant.now().plusSeconds(3600)
        );
    }

    @Test
    void mapsEveryFieldFromTheEnvelopeAndPayload() {
        RequestApprovalCommand command = WorkflowApprovalRequiredEventMapper.toCommand(envelope(Map.of()), fullPayload());

        assertThat(command.requestKey()).isEqualTo("wf-1");
        assertThat(command.requestHash()).isEqualTo("hash-1");
        assertThat(command.sourceDomain()).isEqualTo("agent-runtime-service");
        assertThat(command.sourceRequestId()).isEqualTo("wf-1");
        assertThat(command.ticketId()).isEqualTo("ticket-1");
        assertThat(command.workflowInstanceId()).isEqualTo("wf-1");
        assertThat(command.toolRequestId()).isNull();
        assertThat(command.requestedBy()).isEqualTo("agent-runtime-service");
        assertThat(command.approvalType()).isEqualTo(ApprovalType.WORKFLOW_ACTION);
        assertThat(command.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(command.constraints()).hasSize(1);
        assertThat(command.expiresAt()).isNotNull();
        assertThat(command.correlationId()).isEqualTo("corr-1");
        assertThat(command.causationId()).isEqualTo("evt-1");
    }

    @Test
    void ticketIdFallsBackToTheEnvelopeWhenThePayloadOmitsIt() {
        WorkflowApprovalRequiredPayload payloadWithoutTicketId = new WorkflowApprovalRequiredPayload("wf-1", null, "HIGH", "hash-1", List.of(), null);

        RequestApprovalCommand command = WorkflowApprovalRequiredEventMapper.toCommand(envelope(Map.of()), payloadWithoutTicketId);

        assertThat(command.ticketId()).isEqualTo("ticket-1");
    }

    @Test
    void expiresAtAndConstraintsAreOptional() {
        WorkflowApprovalRequiredPayload minimal = new WorkflowApprovalRequiredPayload("wf-1", "ticket-1", "HIGH", "hash-1", null, null);

        RequestApprovalCommand command = WorkflowApprovalRequiredEventMapper.toCommand(envelope(Map.of()), minimal);

        assertThat(command.expiresAt()).isNull();
        assertThat(command.constraints()).isEmpty();
    }

    @Test
    void rejectsAMissingWorkflowInstanceId() {
        WorkflowApprovalRequiredPayload payload = new WorkflowApprovalRequiredPayload(null, "ticket-1", "HIGH", "hash-1", List.of(), null);

        assertThatThrownBy(() -> WorkflowApprovalRequiredEventMapper.toCommand(envelope(Map.of()), payload))
            .isInstanceOf(ConsumedEventSchemaInvalidException.class);
    }

    @Test
    void rejectsAMissingInputHash() {
        WorkflowApprovalRequiredPayload payload = new WorkflowApprovalRequiredPayload("wf-1", "ticket-1", "HIGH", null, List.of(), null);

        assertThatThrownBy(() -> WorkflowApprovalRequiredEventMapper.toCommand(envelope(Map.of()), payload))
            .isInstanceOf(ConsumedEventSchemaInvalidException.class);
    }

    @Test
    void rejectsAnUnrecognizedRiskLevel() {
        WorkflowApprovalRequiredPayload payload = new WorkflowApprovalRequiredPayload("wf-1", "ticket-1", "SUPER_HIGH", "hash-1", List.of(), null);

        assertThatThrownBy(() -> WorkflowApprovalRequiredEventMapper.toCommand(envelope(Map.of()), payload))
            .isInstanceOf(ConsumedEventSchemaInvalidException.class);
    }
}
