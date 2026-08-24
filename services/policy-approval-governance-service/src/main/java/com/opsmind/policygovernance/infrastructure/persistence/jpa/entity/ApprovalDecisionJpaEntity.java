package com.opsmind.policygovernance.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(schema = "governance", name = "approval_decisions")
public class ApprovalDecisionJpaEntity {

    @Id
    @Column(name = "approval_decision_id")
    private String approvalDecisionId;

    /**
     * The DB-level {@code UNIQUE (approval_request_id)} constraint
     * (V006__create_approval_decisions.sql) is what actually guarantees
     * "one final decision per approval request" under concurrent
     * grant/deny attempts — a second concurrent insert fails with a
     * constraint violation rather than silently overwriting the first.
     */
    @Column(name = "approval_request_id", nullable = false, unique = true)
    private String approvalRequestId;

    @Column(name = "decision", nullable = false)
    private String decision;

    /** Deferred to phase-03's real actor-type model. */
    @Column(name = "decided_by_type")
    private String decidedByType;

    @Column(name = "decided_by_id", nullable = false)
    private String decidedById;

    @Column(name = "reason", nullable = false)
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "conditions", nullable = false, columnDefinition = "jsonb")
    private String conditionsJson;

    @Column(name = "separation_of_duties_result", nullable = false)
    private boolean separationOfDutiesResult;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    /** SPEC-PG-011: 09-concurrency-and-idempotency's "approval command: approvalRequestId + commandIdempotencyKey" key. */
    @Column(name = "command_idempotency_key", nullable = false)
    private String commandIdempotencyKey;

    /** SPEC-PG-016: nullable — 06 has no session store of its own; captured verbatim for the audit trail. */
    @Column(name = "session_id")
    private String sessionId;

    /** SPEC-PG-016: nullable — see {@link #sessionId}. */
    @Column(name = "device_id")
    private String deviceId;

    /** SPEC-PG-016: {@code false} unless the caller supplied a verified MFA/step-up marker. */
    @Column(name = "step_up_verified", nullable = false)
    private boolean stepUpVerified;

    protected ApprovalDecisionJpaEntity() {
    }

    public ApprovalDecisionJpaEntity(
        String approvalDecisionId, String approvalRequestId, String decision, String decidedByType,
        String decidedById, String reason, String conditionsJson, boolean separationOfDutiesResult, Instant decidedAt,
        String commandIdempotencyKey, String sessionId, String deviceId, boolean stepUpVerified
    ) {
        this.approvalDecisionId = approvalDecisionId;
        this.approvalRequestId = approvalRequestId;
        this.decision = decision;
        this.decidedByType = decidedByType;
        this.decidedById = decidedById;
        this.reason = reason;
        this.conditionsJson = conditionsJson;
        this.separationOfDutiesResult = separationOfDutiesResult;
        this.decidedAt = decidedAt;
        this.commandIdempotencyKey = commandIdempotencyKey;
        this.sessionId = sessionId;
        this.deviceId = deviceId;
        this.stepUpVerified = stepUpVerified;
    }

    public String getApprovalDecisionId() {
        return approvalDecisionId;
    }

    public String getApprovalRequestId() {
        return approvalRequestId;
    }

    public String getDecision() {
        return decision;
    }

    public String getDecidedByType() {
        return decidedByType;
    }

    public String getDecidedById() {
        return decidedById;
    }

    public String getReason() {
        return reason;
    }

    public String getConditionsJson() {
        return conditionsJson;
    }

    public boolean isSeparationOfDutiesResult() {
        return separationOfDutiesResult;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getCommandIdempotencyKey() {
        return commandIdempotencyKey;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public boolean isStepUpVerified() {
        return stepUpVerified;
    }
}
