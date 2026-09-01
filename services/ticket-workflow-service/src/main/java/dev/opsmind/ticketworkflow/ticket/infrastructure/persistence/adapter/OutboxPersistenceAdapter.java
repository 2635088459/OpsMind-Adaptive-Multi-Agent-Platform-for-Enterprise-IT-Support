package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.EventSchemaValidator;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.entity.OutboxEventJpaEntity;
import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.repository.SpringDataOutboxEventJpaRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
public class OutboxPersistenceAdapter implements OutboxEventRepository {

    /**
     * Project-level integration verification (2026-09-01): {@code
     * SELECT ... FOR UPDATE SKIP LOCKED} isn't expressible through Spring
     * Data derived queries, so the claim/mark-published/mark-retry side
     * uses the same {@code NamedParameterJdbcTemplate} raw-SQL style
     * already established elsewhere in this codebase (see {@code
     * CorrectionEventPersistenceAdapter}) rather than introducing a new
     * convention.
     */
    private static final String CLAIM_SQL = """
        UPDATE ticket.outbox_events
        SET locked_by = :workerId, locked_at = :now
        WHERE outbox_id IN (
            SELECT outbox_id
            FROM ticket.outbox_events
            WHERE published_at IS NULL
              AND available_at <= :now
              AND (locked_at IS NULL OR locked_at <= :staleLockThreshold)
            ORDER BY available_at, created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
        )
        RETURNING outbox_id, event_id, event_type, event_version, routing_key, aggregate_type,
                  aggregate_id, aggregate_version, ticket_id, workflow_id, trace_id, correlation_id,
                  causation_id, data_classification, payload::text AS payload_json, created_at, available_at
        """;

    private static final String MARK_PUBLISHED_SQL = """
        UPDATE ticket.outbox_events
        SET published_at = :publishedAt, locked_by = NULL, locked_at = NULL
        WHERE outbox_id = :outboxId
        """;

    private static final String MARK_RETRY_SQL = """
        UPDATE ticket.outbox_events
        SET publish_attempts = :attempts, available_at = :nextAvailableAt,
            last_publish_error_code = :errorCode, last_publish_error_at = :now,
            locked_by = NULL, locked_at = NULL
        WHERE outbox_id = :outboxId
        """;

    private final SpringDataOutboxEventJpaRepository repository;
    private final EventSchemaValidator eventSchemaValidator;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OutboxPersistenceAdapter(
        SpringDataOutboxEventJpaRepository repository,
        EventSchemaValidator eventSchemaValidator,
        NamedParameterJdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.eventSchemaValidator = eventSchemaValidator;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void append(OutboxEventEntry entry) {
        eventSchemaValidator.validate(entry.eventType(), entry.eventVersion(), entry.payload());

        repository.save(new OutboxEventJpaEntity(
            entry.outboxId(),
            entry.eventId(),
            entry.eventType(),
            entry.eventVersion(),
            entry.routingKey(),
            entry.aggregateType(),
            entry.aggregateId(),
            entry.aggregateVersion(),
            entry.ticketId().value(),
            entry.workflowId(),
            entry.traceId(),
            entry.correlationId(),
            entry.causationId(),
            entry.dataClassification(),
            entry.payload(),
            Map.of(),
            entry.createdAt(),
            entry.availableAt()
        ));
    }

    @Override
    public java.util.List<OutboxEventEntry> claimPublishable(Instant now, Instant staleLockThreshold, String workerId, int batchSize) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("now", Timestamp.from(now))
            .addValue("staleLockThreshold", Timestamp.from(staleLockThreshold))
            .addValue("workerId", workerId)
            .addValue("batchSize", batchSize);

        return jdbcTemplate.query(CLAIM_SQL, params, (rs, rowNum) -> new OutboxEventEntry(
            (UUID) rs.getObject("outbox_id"),
            rs.getString("event_id"),
            rs.getString("event_type"),
            rs.getString("event_version"),
            rs.getString("routing_key"),
            rs.getString("aggregate_type"),
            rs.getString("aggregate_id"),
            rs.getLong("aggregate_version"),
            TicketId.of((UUID) rs.getObject("ticket_id")),
            rs.getString("workflow_id"),
            rs.getString("trace_id"),
            rs.getString("correlation_id"),
            rs.getString("causation_id"),
            rs.getString("data_classification"),
            readPayload(rs.getString("payload_json")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("available_at").toInstant()
        ));
    }

    @Override
    public void markPublished(UUID outboxId, Instant publishedAt) {
        jdbcTemplate.update(MARK_PUBLISHED_SQL, new MapSqlParameterSource()
            .addValue("outboxId", outboxId)
            .addValue("publishedAt", Timestamp.from(publishedAt)));
    }

    @Override
    public void markRetry(UUID outboxId, int attempts, Instant nextAvailableAt, String errorCode) {
        jdbcTemplate.update(MARK_RETRY_SQL, new MapSqlParameterSource()
            .addValue("outboxId", outboxId)
            .addValue("attempts", attempts)
            .addValue("nextAvailableAt", Timestamp.from(nextAvailableAt))
            .addValue("errorCode", errorCode)
            .addValue("now", Timestamp.from(Instant.now())));
    }

    private Map<String, Object> readPayload(String payloadJson) {
        try {
            return objectMapper.readValue(payloadJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("failed to deserialize outbox payload JSON", e);
        }
    }
}
