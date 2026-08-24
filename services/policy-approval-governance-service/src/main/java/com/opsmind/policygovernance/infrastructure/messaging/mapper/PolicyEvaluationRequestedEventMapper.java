package com.opsmind.policygovernance.infrastructure.messaging.mapper;

import com.opsmind.policygovernance.application.command.EvaluateDecisionCommand;
import com.opsmind.policygovernance.infrastructure.messaging.consumer.ConsumedEventSchemaInvalidException;
import com.opsmind.policygovernance.infrastructure.messaging.contract.ConsumedEventEnvelope;
import com.opsmind.policygovernance.infrastructure.messaging.contract.PolicyEvaluationRequestedPayload;

/**
 * SPEC-PG-028: pure envelope+payload -&gt; {@link EvaluateDecisionCommand}
 * translation, mirroring {@link ToolApprovalRequiredEventMapper}'s own
 * shape but targeting {@code PolicyDecisionService#evaluate} instead of
 * {@code ApprovalService#request} — this is a policy-decision request, not
 * an approval request. {@code sourceDomain} = {@code envelope.producer()}
 * (no human actor is named on this event either); {@code causationId} =
 * {@code envelope.eventId()}.
 */
public final class PolicyEvaluationRequestedEventMapper {

    private PolicyEvaluationRequestedEventMapper() {
    }

    public static EvaluateDecisionCommand toCommand(ConsumedEventEnvelope envelope, PolicyEvaluationRequestedPayload payload) {
        requireNonBlank(payload.decisionKey(), "payload.decisionKey");
        requireNonBlank(payload.inputHash(), "payload.inputHash");
        requireNonBlank(payload.subjectType(), "payload.subjectType");
        requireNonBlank(payload.subjectId(), "payload.subjectId");
        requireNonBlank(payload.actionType(), "payload.actionType");
        requireNonBlank(payload.policyId(), "payload.policyId");
        requireNonBlank(payload.sourceRequestId(), "payload.sourceRequestId");
        requireNonBlank(envelope.producer(), "producer");
        requireNonBlank(envelope.correlationId(), "correlationId");
        requireNonBlank(envelope.eventId(), "eventId");

        String ticketId = payload.ticketId() != null ? payload.ticketId() : envelope.ticketId();

        return new EvaluateDecisionCommand(
            payload.decisionKey(), payload.inputHash(), payload.subjectType(), payload.subjectId(), payload.actionType(),
            payload.readOnly(), payload.resourceType(), payload.resourceId(), payload.tenantId(), envelope.producer(),
            payload.sourceRequestId(), ticketId, payload.workflowInstanceId(), payload.policyId(), envelope.correlationId(),
            envelope.eventId()
        );
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ConsumedEventSchemaInvalidException(fieldName + " is required on policy.evaluation.requested.v1");
        }
    }
}
