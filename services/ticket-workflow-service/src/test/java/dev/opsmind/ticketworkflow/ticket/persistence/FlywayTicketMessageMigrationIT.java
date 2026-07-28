package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractAddTicketMessageIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class FlywayTicketMessageMigrationIT extends AbstractAddTicketMessageIT {

    @Test
    void shouldCreateTicketMessagesTable() {
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'ticket' AND table_name = ?",
            Integer.class, "ticket_messages"
        )).isEqualTo(1);
    }

    @Test
    void shouldCreateTicketQueryIndex() {
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE schemaname = 'ticket' AND tablename = 'ticket_messages' AND indexname = ?",
            Integer.class, "ix_ticket_messages_ticket_created"
        )).isEqualTo(1);
    }

    @Test
    void shouldRecordAllMigrationsAsSuccessful() {
        Integer failedCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM public.flyway_schema_history WHERE success = false", Integer.class
        );
        assertThat(failedCount).isZero();

        Integer appliedCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM public.flyway_schema_history WHERE success = true", Integer.class
        );
        assertThat(appliedCount).isGreaterThanOrEqualTo(11);
    }
}
