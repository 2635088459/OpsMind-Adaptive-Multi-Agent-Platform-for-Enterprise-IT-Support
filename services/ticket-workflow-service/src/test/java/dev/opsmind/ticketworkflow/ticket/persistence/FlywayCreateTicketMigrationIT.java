package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractCreateTicketIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class FlywayCreateTicketMigrationIT extends AbstractCreateTicketIT {

    @Test
    void shouldCreateAllTicketWorkflowTables() {
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'ticket' AND table_name = ?",
            Integer.class, "tickets"
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'ticket' AND table_name = ?",
            Integer.class, "ticket_resolution_cycles"
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'ticket' AND table_name = ?",
            Integer.class, "ticket_sla_cycles"
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'ticket' AND table_name = ?",
            Integer.class, "ticket_status_history"
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'ticket' AND table_name = ?",
            Integer.class, "audit_records"
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'ticket' AND table_name = ?",
            Integer.class, "outbox_events"
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'ticket' AND table_name = ?",
            Integer.class, "idempotency_records"
        )).isEqualTo(1);
    }

    @Test
    void shouldCreateDisplayIdSequence() {
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.sequences WHERE sequence_schema = 'ticket' AND sequence_name = 'ticket_display_id_seq'",
            Integer.class
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
        assertThat(appliedCount).isGreaterThanOrEqualTo(8);
    }
}
