package dev.opsmind.ticketworkflow.ticket.infrastructure.identity;

import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketDisplayIdGenerator;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Generates {@code INC-<number>} display ids from a PostgreSQL sequence,
 * which makes collisions practically impossible while still allowing the
 * bounded regeneration retry required by SPEC-TW-001 §10 to be exercised
 * (e.g. against artificially seeded data in tests).
 */
@Component
public class SequenceBackedTicketDisplayIdGenerator implements TicketDisplayIdGenerator {

    private final JdbcTemplate jdbcTemplate;

    public SequenceBackedTicketDisplayIdGenerator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public TicketDisplayId generate() {
        Long nextValue = jdbcTemplate.queryForObject("SELECT nextval('ticket.ticket_display_id_seq')", Long.class);
        return TicketDisplayId.fromSequence(nextValue);
    }
}
