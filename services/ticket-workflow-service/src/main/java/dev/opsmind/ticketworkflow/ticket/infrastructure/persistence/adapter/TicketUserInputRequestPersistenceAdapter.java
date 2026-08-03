package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketUserInputRequestRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketUserInputRequestUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketUserInputRequestUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * SPEC-TW-012 persistence: the ticket-row update and the new open
 * user-input-request insert, executed as two statements against the same
 * Spring-managed transaction (mirrors {@code TicketResolvePersistenceAdapter},
 * SPEC-TW-010). The partial unique index ({@code
 * uq_ticket_one_open_user_input_request}) is defense-in-depth only — the
 * ticket UPDATE's {@code status = 'IN_PROGRESS'} guard already prevents two
 * concurrent commands from both reaching the INSERT for the same ticket.
 */
@Component
public class TicketUserInputRequestPersistenceAdapter implements TicketUserInputRequestRepository {

    private static final String UPDATE_TICKET_SQL = """
        UPDATE ticket.tickets
        SET status = 'WAITING_FOR_USER',
            waiting_for_requester_since = :requestedAt,
            updated_at = :updatedAt,
            version = version + 1
        WHERE ticket_id = :ticketId
          AND version = :expectedVersion
          AND status = 'IN_PROGRESS'
          AND current_support_user_id IS NOT NULL
        """;

    private static final String INSERT_REQUEST_SQL = """
        INSERT INTO ticket.ticket_user_input_requests
            (request_id, ticket_id, request_status, prompt, requested_fields, requested_by_type, requested_by_id,
             requested_at, resume_status, expires_at, correlation_id)
        VALUES (:requestId, :ticketId, 'OPEN', :prompt, CAST(:requestedFields AS jsonb), :requestedByType, :requestedById,
                :requestedAt, :resumeStatus, :expiresAt, :correlationId)
        """;

    private static final String RECLASSIFY_SQL = """
        SELECT status, version, current_support_user_id
        FROM ticket.tickets
        WHERE ticket_id = :ticketId
        """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TicketUserInputRequestPersistenceAdapter(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public TicketUserInputRequestUpdateOutcome applyRequestUserInput(TicketUserInputRequestUpdate update) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("requestId", update.requestId())
            .addValue("prompt", update.prompt())
            .addValue("requestedFields", writeRequestedFields(update.requestedFields()))
            .addValue("requestedByType", update.requestedByType())
            .addValue("requestedById", update.requestedById())
            .addValue("requestedAt", Timestamp.from(update.requestedAt()))
            .addValue("resumeStatus", update.resumeStatus())
            .addValue("expiresAt", update.expiresAt() == null ? null : Timestamp.from(update.expiresAt()))
            .addValue("correlationId", update.correlationId())
            .addValue("updatedAt", Timestamp.from(update.updatedAt()))
            .addValue("ticketId", update.ticketId().value())
            .addValue("expectedVersion", update.expectedVersion());

        int ticketRowsAffected = jdbcTemplate.update(UPDATE_TICKET_SQL, params);
        if (ticketRowsAffected != 1) {
            return reclassify(update);
        }

        try {
            jdbcTemplate.update(INSERT_REQUEST_SQL, params);
        } catch (DataIntegrityViolationException e) {
            return new TicketUserInputRequestUpdateOutcome.RequestAlreadyOpen();
        }

        return new TicketUserInputRequestUpdateOutcome.Created(update.expectedVersion() + 1);
    }

    private String writeRequestedFields(List<String> requestedFields) {
        if (requestedFields == null || requestedFields.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(requestedFields);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize requestedFields", e);
        }
    }

    private TicketUserInputRequestUpdateOutcome reclassify(TicketUserInputRequestUpdate update) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            RECLASSIFY_SQL, new MapSqlParameterSource("ticketId", update.ticketId().value())
        );
        if (rows.isEmpty()) {
            return new TicketUserInputRequestUpdateOutcome.TicketMissing();
        }

        Map<String, Object> row = rows.get(0);
        TicketStatus currentStatus = TicketStatus.valueOf((String) row.get("status"));
        long currentVersion = ((Number) row.get("version")).longValue();
        String currentAssigneeId = (String) row.get("current_support_user_id");

        if (currentVersion != update.expectedVersion()) {
            return new TicketUserInputRequestUpdateOutcome.VersionMismatch(currentVersion);
        }
        if (currentAssigneeId == null) {
            return new TicketUserInputRequestUpdateOutcome.NotAssigned();
        }
        return new TicketUserInputRequestUpdateOutcome.InvalidState(currentStatus);
    }
}
