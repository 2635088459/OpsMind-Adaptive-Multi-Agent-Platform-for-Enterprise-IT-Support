package dev.opsmind.ticketworkflow.ticket.infrastructure.persistence.adapter;

import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketUserReplyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketUserReplyResumeUpdate;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketUserReplyResumeUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * SPEC-TW-013 persistence: the ticket-row resume update and the request
 * row's {@code ANSWERED} completion, executed as two statements against the
 * same Spring-managed transaction the caller's {@code @Transactional}
 * service method already opened for the message insert (mirrors {@code
 * TicketResolvePersistenceAdapter}, SPEC-TW-010).
 */
@Component
public class TicketUserReplyPersistenceAdapter implements TicketUserReplyRepository {

    private static final String UPDATE_TICKET_SQL = """
        UPDATE ticket.tickets
        SET status = 'IN_PROGRESS',
            waiting_for_requester_since = NULL,
            updated_at = :updatedAt,
            version = version + 1
        WHERE ticket_id = :ticketId
          AND version = :expectedVersion
          AND status = 'WAITING_FOR_USER'
        """;

    private static final String UPDATE_REQUEST_SQL = """
        UPDATE ticket.ticket_user_input_requests
        SET request_status = 'ANSWERED',
            answered_message_id = :answeredMessageId,
            answered_at = :answeredAt
        WHERE request_id = :requestId
          AND ticket_id = :ticketId
          AND request_status = 'OPEN'
        """;

    private static final String RECLASSIFY_SQL = """
        SELECT status, version FROM ticket.tickets WHERE ticket_id = :ticketId
        """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public TicketUserReplyPersistenceAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public TicketUserReplyResumeUpdateOutcome applyResume(TicketUserReplyResumeUpdate update) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("answeredMessageId", update.answeredMessageId())
            .addValue("answeredAt", Timestamp.from(update.answeredAt()))
            .addValue("updatedAt", Timestamp.from(update.updatedAt()))
            .addValue("ticketId", update.ticketId().value())
            .addValue("expectedVersion", update.expectedVersion())
            .addValue("requestId", update.requestId());

        int ticketRowsAffected = jdbcTemplate.update(UPDATE_TICKET_SQL, params);
        if (ticketRowsAffected != 1) {
            return reclassify(update);
        }

        int requestRowsAffected = jdbcTemplate.update(UPDATE_REQUEST_SQL, params);
        if (requestRowsAffected != 1) {
            return new TicketUserReplyResumeUpdateOutcome.RequestNotOpen();
        }

        return new TicketUserReplyResumeUpdateOutcome.Updated(update.expectedVersion() + 1);
    }

    private TicketUserReplyResumeUpdateOutcome reclassify(TicketUserReplyResumeUpdate update) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            RECLASSIFY_SQL, new MapSqlParameterSource("ticketId", update.ticketId().value())
        );
        if (rows.isEmpty()) {
            return new TicketUserReplyResumeUpdateOutcome.TicketMissing();
        }

        Map<String, Object> row = rows.get(0);
        TicketStatus currentStatus = TicketStatus.valueOf((String) row.get("status"));
        long currentVersion = ((Number) row.get("version")).longValue();

        if (currentVersion != update.expectedVersion()) {
            return new TicketUserReplyResumeUpdateOutcome.VersionMismatch(currentVersion);
        }
        return new TicketUserReplyResumeUpdateOutcome.InvalidState(currentStatus);
    }
}
