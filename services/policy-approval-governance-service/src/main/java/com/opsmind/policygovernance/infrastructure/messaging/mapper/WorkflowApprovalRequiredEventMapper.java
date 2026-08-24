package com.opsmind.policygovernance.infrastructure.messaging.mapper;

import com.opsmind.policygovernance.application.command.RequestApprovalCommand;
import com.opsmind.policygovernance.domain.approval.ApprovalType;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import com.opsmind.policygovernance.infrastructure.messaging.consumer.ConsumedEventSchemaInvalidException;
import com.opsmind.policygovernance.infrastructure.messaging.contract.ConsumedEventEnvelope;
import com.opsmind.policygovernance.infrastructure.messaging.contract.WorkflowApprovalRequiredPayload;

import java.util.List;

/**
 * SPEC-PG-026: pure envelope+payload -&gt; {@link RequestApprovalCommand}
 * translation, mirroring {@link ToolApprovalRequiredEventMapper}'s own
 * field-derivation reasoning with {@code workflowInstanceId} standing in
 * for {@code toolRequestId} as the primary business key — see that type's
 * own javadoc for why each field is derived the way it is.
 */
public final class WorkflowApprovalRequiredEventMapper {

    private WorkflowApprovalRequiredEventMapper() {
    }

    public static RequestApprovalCommand toCommand(ConsumedEventEnvelope envelope, WorkflowApprovalRequiredPayload payload) {
        requireNonBlank(payload.workflowInstanceId(), "payload.workflowInstanceId");
        requireNonBlank(payload.inputHash(), "payload.inputHash");
        requireNonBlank(payload.riskLevel(), "payload.riskLevel");
        requireNonBlank(envelope.producer(), "producer");
        requireNonBlank(envelope.correlationId(), "correlationId");
        requireNonBlank(envelope.eventId(), "eventId");

        RiskLevel riskLevel = parseRiskLevel(payload.riskLevel());
        String ticketId = payload.ticketId() != null ? payload.ticketId() : envelope.ticketId();

        return new RequestApprovalCommand(
            payload.workflowInstanceId(), payload.inputHash(), envelope.producer(), payload.workflowInstanceId(),
            ticketId, payload.workflowInstanceId(), null, null, null,
            envelope.producer(), ApprovalType.WORKFLOW_ACTION, riskLevel,
            payload.constraints() == null ? List.of() : payload.constraints(), payload.expiresAt(),
            envelope.correlationId(), envelope.eventId()
        );
    }

    private static RiskLevel parseRiskLevel(String value) {
        try {
            return RiskLevel.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new ConsumedEventSchemaInvalidException("payload.riskLevel is not a recognized RiskLevel: " + value, e);
        }
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ConsumedEventSchemaInvalidException(fieldName + " is required on workflow.approval.required.v1");
        }
    }
}
