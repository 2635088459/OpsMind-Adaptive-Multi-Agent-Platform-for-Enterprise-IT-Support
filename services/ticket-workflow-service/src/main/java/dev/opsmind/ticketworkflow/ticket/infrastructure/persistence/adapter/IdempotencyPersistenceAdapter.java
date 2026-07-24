package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyCompletion;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationOutcome;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationRequest;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.entity.IdempotencyRecordJpaEntity;
import dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.jpa.repository.SpringDataIdempotencyRecordJpaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Reserves idempotency records using a native {@code INSERT ... ON CONFLICT
 * DO NOTHING} statement (08-transaction-and-outbox §7 / 09-concurrency-and-
 * idempotency §8). A native statement is used deliberately: PostgreSQL aborts
 * the enclosing transaction after a constraint-violation exception, which
 * would break the single-transaction Create Ticket flow, whereas {@code ON
 * CONFLICT DO NOTHING} simply reports zero affected rows without aborting.
 */
@Component
public class IdempotencyPersistenceAdapter implements IdempotencyRepository {

    private static final String RESERVE_SQL = """
        INSERT INTO ticket.idempotency_records
            (idempotency_record_id, actor_scope, idempotency_key, operation_id, request_hash, status, created_at, expires_at)
        VALUES (?1, ?2, ?3, ?4, ?5, 'IN_PROGRESS', ?6, ?7)
        ON CONFLICT (actor_scope, idempotency_key) DO NOTHING
        """;

    private static final String RECLAIM_STALE_SQL = """
        UPDATE ticket.idempotency_records
        SET created_at = ?1
        WHERE idempotency_record_id = ?2 AND status = 'IN_PROGRESS'
        """;

    @PersistenceContext
    private EntityManager entityManager;

    private final SpringDataIdempotencyRecordJpaRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotencyPersistenceAdapter(SpringDataIdempotencyRecordJpaRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    public IdempotencyReservationOutcome reserve(IdempotencyReservationRequest request) {
        int inserted = entityManager.createNativeQuery(RESERVE_SQL)
            .setParameter(1, request.idempotencyRecordId())
            .setParameter(2, request.actorScope())
            .setParameter(3, request.idempotencyKey())
            .setParameter(4, request.operationId())
            .setParameter(5, request.requestHash())
            .setParameter(6, java.sql.Timestamp.from(request.now()))
            .setParameter(7, java.sql.Timestamp.from(request.now().plus(request.ttl())))
            .executeUpdate();

        if (inserted == 1) {
            return new IdempotencyReservationOutcome.Reserved(request.idempotencyRecordId());
        }

        return resolveExistingReservation(request);
    }

    private IdempotencyReservationOutcome resolveExistingReservation(IdempotencyReservationRequest request) {
        IdempotencyRecordJpaEntity existing = repository
            .findByActorScopeAndIdempotencyKey(request.actorScope(), request.idempotencyKey())
            .orElseThrow(() -> new IllegalStateException(
                "idempotency reservation conflicted but no existing record was found for actorScope="
                    + request.actorScope()));

        boolean sameHash = existing.getRequestHash().equals(request.requestHash());

        return switch (existing.getStatus()) {
            case "COMPLETED" -> sameHash
                ? new IdempotencyReservationOutcome.Replayed(existing.getResponseStatus(), writeJson(existing.getResponseBody()))
                : new IdempotencyReservationOutcome.KeyReused();
            case "IN_PROGRESS" -> resolveInProgress(existing, request);
            case "FAILED_RETRYABLE" -> reclaim(existing, request.now());
            default -> new IdempotencyReservationOutcome.KeyReused();
        };
    }

    private IdempotencyReservationOutcome resolveInProgress(IdempotencyRecordJpaEntity existing, IdempotencyReservationRequest request) {
        boolean stale = existing.getCreatedAt().isBefore(request.now().minus(request.staleThreshold()));
        if (!stale) {
            return new IdempotencyReservationOutcome.RequestInProgress();
        }
        return reclaim(existing, request.now());
    }

    private IdempotencyReservationOutcome reclaim(IdempotencyRecordJpaEntity existing, Instant now) {
        entityManager.createNativeQuery(RECLAIM_STALE_SQL)
            .setParameter(1, java.sql.Timestamp.from(now))
            .setParameter(2, existing.getIdempotencyRecordId())
            .executeUpdate();
        entityManager.detach(existing);
        return new IdempotencyReservationOutcome.Reserved(existing.getIdempotencyRecordId());
    }

    @Override
    public void complete(UUID idempotencyRecordId, IdempotencyCompletion completion) {
        IdempotencyRecordJpaEntity entity = repository.findById(idempotencyRecordId)
            .orElseThrow(() -> new IllegalStateException("idempotency record not found: " + idempotencyRecordId));

        entity.setStatus("COMPLETED");
        entity.setResourceType(completion.resourceType());
        entity.setResourceId(completion.resourceId());
        entity.setResponseStatus(completion.responseStatus());
        entity.setResponseBody(readJson(completion.responseBodyJson()));
        entity.setCompletedAt(completion.completedAt());
    }

    private Map<String, Object> readJson(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            return map;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to parse idempotency response body", e);
        }
    }

    private String writeJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize stored idempotency response body", e);
        }
    }
}
