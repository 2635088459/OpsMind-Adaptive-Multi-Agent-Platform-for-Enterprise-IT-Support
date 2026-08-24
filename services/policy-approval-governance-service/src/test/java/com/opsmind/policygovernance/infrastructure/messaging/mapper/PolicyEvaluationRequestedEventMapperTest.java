package com.opsmind.policygovernance.infrastructure.messaging.mapper;

import com.opsmind.policygovernance.application.command.EvaluateDecisionCommand;
import com.opsmind.policygovernance.infrastructure.messaging.consumer.ConsumedEventSchemaInvalidException;
import com.opsmind.policygovernance.infrastructure.messaging.contract.ConsumedEventEnvelope;
import com.opsmind.policygovernance.infrastructure.messaging.contract.PolicyEvaluationRequestedPayload;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SPEC-PG-028: pure mapping unit tests — no Spring, no RabbitMQ. Mirrors {@code ToolApprovalRequiredEventMapperTest}. */
@Tag("unit")
class PolicyEvaluationRequestedEventMapperTest {

    private ConsumedEventEnvelope envelope(Map<String, Object> payloadMap) {
        return new ConsumedEventEnvelope(
            "evt-1", "policy.evaluation.requested.v1", "memory-knowledge-service", 1,
            "mem-1", "ticket-1", "corr-1", "cause-0", Instant.parse("2026-08-23T00:00:00Z"), payloadMap
        );
    }

    private PolicyEvaluationRequestedPayload fullPayload() {
        return new PolicyEvaluationRequestedPayload(
            "dk-1", "hash-1", "user", "user-1", "READ", true, "memory-record", "mem-1", "tenant-1",
            "src-req-1", "ticket-1", null, "policy-1"
        );
    }

    @Test
    void mapsEveryFieldFromTheEnvelopeAndPayload() {
        EvaluateDecisionCommand command = PolicyEvaluationRequestedEventMapper.toCommand(envelope(Map.of()), fullPayload());

        assertThat(command.decisionKey()).isEqualTo("dk-1");
        assertThat(command.inputHash()).isEqualTo("hash-1");
        assertThat(command.subjectType()).isEqualTo("user");
        assertThat(command.subjectId()).isEqualTo("user-1");
        assertThat(command.actionType()).isEqualTo("READ");
        assertThat(command.readOnly()).isTrue();
        assertThat(command.resourceType()).isEqualTo("memory-record");
        assertThat(command.resourceId()).isEqualTo("mem-1");
        assertThat(command.tenantId()).isEqualTo("tenant-1");
        assertThat(command.sourceDomain()).isEqualTo("memory-knowledge-service");
        assertThat(command.sourceRequestId()).isEqualTo("src-req-1");
        assertThat(command.ticketId()).isEqualTo("ticket-1");
        assertThat(command.workflowInstanceId()).isNull();
        assertThat(command.policyId()).isEqualTo("policy-1");
        assertThat(command.correlationId()).isEqualTo("corr-1");
        assertThat(command.causationId()).isEqualTo("evt-1");
    }

    @Test
    void ticketIdFallsBackToTheEnvelopeWhenThePayloadOmitsIt() {
        PolicyEvaluationRequestedPayload payloadWithoutTicketId = new PolicyEvaluationRequestedPayload(
            "dk-1", "hash-1", "user", "user-1", "READ", false, null, null, null, "src-req-1", null, null, "policy-1"
        );

        EvaluateDecisionCommand command = PolicyEvaluationRequestedEventMapper.toCommand(envelope(Map.of()), payloadWithoutTicketId);

        assertThat(command.ticketId()).isEqualTo("ticket-1");
    }

    @Test
    void rejectsAMissingDecisionKey() {
        PolicyEvaluationRequestedPayload payload = new PolicyEvaluationRequestedPayload(
            null, "hash-1", "user", "user-1", "READ", false, null, null, null, "src-req-1", null, null, "policy-1"
        );

        assertThatThrownBy(() -> PolicyEvaluationRequestedEventMapper.toCommand(envelope(Map.of()), payload))
            .isInstanceOf(ConsumedEventSchemaInvalidException.class);
    }

    @Test
    void rejectsAMissingInputHash() {
        PolicyEvaluationRequestedPayload payload = new PolicyEvaluationRequestedPayload(
            "dk-1", null, "user", "user-1", "READ", false, null, null, null, "src-req-1", null, null, "policy-1"
        );

        assertThatThrownBy(() -> PolicyEvaluationRequestedEventMapper.toCommand(envelope(Map.of()), payload))
            .isInstanceOf(ConsumedEventSchemaInvalidException.class);
    }

    @Test
    void rejectsAMissingPolicyId() {
        PolicyEvaluationRequestedPayload payload = new PolicyEvaluationRequestedPayload(
            "dk-1", "hash-1", "user", "user-1", "READ", false, null, null, null, "src-req-1", null, null, null
        );

        assertThatThrownBy(() -> PolicyEvaluationRequestedEventMapper.toCommand(envelope(Map.of()), payload))
            .isInstanceOf(ConsumedEventSchemaInvalidException.class);
    }

    @Test
    void rejectsAMissingSourceRequestId() {
        PolicyEvaluationRequestedPayload payload = new PolicyEvaluationRequestedPayload(
            "dk-1", "hash-1", "user", "user-1", "READ", false, null, null, null, null, null, null, "policy-1"
        );

        assertThatThrownBy(() -> PolicyEvaluationRequestedEventMapper.toCommand(envelope(Map.of()), payload))
            .isInstanceOf(ConsumedEventSchemaInvalidException.class);
    }
}
