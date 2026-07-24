package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(schema = "ticket", name = "idempotency_records")
public class IdempotencyRecordJpaEntity {

    @Id
    @Column(name = "idempotency_record_id")
    private UUID idempotencyRecordId;

    @Column(name = "actor_scope", nullable = false)
    private String actorScope;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "operation_id", nullable = false)
    private String operationId;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "resource_type")
    private String resourceType;

    @Column(name = "resource_id")
    private String resourceId;

    @Column(name = "response_status")
    private Integer responseStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body")
    private Map<String, Object> responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IdempotencyRecordJpaEntity() {
    }

    public IdempotencyRecordJpaEntity(
        UUID idempotencyRecordId,
        String actorScope,
        String idempotencyKey,
        String operationId,
        String requestHash,
        String status,
        String resourceType,
        String resourceId,
        Integer responseStatus,
        Map<String, Object> responseBody,
        Instant createdAt,
        Instant completedAt,
        Instant expiresAt
    ) {
        this.idempotencyRecordId = idempotencyRecordId;
        this.actorScope = actorScope;
        this.idempotencyKey = idempotencyKey;
        this.operationId = operationId;
        this.requestHash = requestHash;
        this.status = status;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
        this.expiresAt = expiresAt;
    }

    public UUID getIdempotencyRecordId() {
        return idempotencyRecordId;
    }

    public String getActorScope() {
        return actorScope;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getOperationId() {
        return operationId;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public void setResponseStatus(Integer responseStatus) {
        this.responseStatus = responseStatus;
    }

    public Map<String, Object> getResponseBody() {
        return responseBody;
    }

    public void setResponseBody(Map<String, Object> responseBody) {
        this.responseBody = responseBody;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
