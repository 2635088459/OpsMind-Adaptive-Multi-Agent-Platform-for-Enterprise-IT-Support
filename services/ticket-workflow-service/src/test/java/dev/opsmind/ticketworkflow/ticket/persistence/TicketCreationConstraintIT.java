package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractCreateTicketIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
class TicketCreationConstraintIT extends AbstractCreateTicketIT {

    @Test
    void shouldRejectDuplicateDisplayId() {
        ResponseEntity<String> response = createTicket("user-constraint-1", newIdempotencyKey(), validRequestBody());
        String location = response.getHeaders().getLocation().toString();
        UUID existingTicketId = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
        String existingDisplayId = jdbcTemplate.queryForObject(
            "SELECT display_id FROM ticket.tickets WHERE ticket_id = ?", String.class, existingTicketId
        );

        assertThatThrownBy(() -> insertMinimalTicket(UUID.randomUUID(), existingDisplayId, UUID.randomUUID()))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldRejectBlankTitle() {
        assertThatThrownBy(() -> insertMinimalTicketWithTitle("   "))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldRejectUnknownStatus() {
        assertThatThrownBy(() -> insertMinimalTicketWithStatus("NOT_A_REAL_STATUS"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldRejectSecondActiveResolutionCycleForSameTicket() {
        UUID ticketId = UUID.randomUUID();
        UUID firstCycleId = UUID.randomUUID();
        insertMinimalTicket(ticketId, "INC-" + System.nanoTime(), firstCycleId);
        insertActiveResolutionCycle(firstCycleId, ticketId, 1);

        assertThatThrownBy(() -> insertActiveResolutionCycle(UUID.randomUUID(), ticketId, 2))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertMinimalTicket(UUID ticketId, String displayId, UUID resolutionCycleId) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("""
            INSERT INTO ticket.tickets
                (ticket_id, display_id, requester_id, title, initial_description, source, application_code,
                 priority, status, current_resolution_cycle_id, created_at, updated_at, version,
                 created_by_type, created_by_id)
            VALUES (?, ?, 'user-constraint-test', 'Constraint test ticket', 'Constraint test description',
                    'PORTAL', 'OTHER', 'UNASSIGNED', 'NEW', ?, ?, ?, 0, 'EMPLOYEE', 'user-constraint-test')
            """, ticketId, displayId, resolutionCycleId, now, now);
    }

    private void insertMinimalTicketWithTitle(String title) {
        UUID ticketId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("""
            INSERT INTO ticket.tickets
                (ticket_id, display_id, requester_id, title, initial_description, source, application_code,
                 priority, status, current_resolution_cycle_id, created_at, updated_at, version,
                 created_by_type, created_by_id)
            VALUES (?, ?, 'user-constraint-test', ?, 'Constraint test description',
                    'PORTAL', 'OTHER', 'UNASSIGNED', 'NEW', ?, ?, ?, 0, 'EMPLOYEE', 'user-constraint-test')
            """, ticketId, "INC-" + System.nanoTime(), title, UUID.randomUUID(), now, now);
    }

    private void insertMinimalTicketWithStatus(String status) {
        UUID ticketId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("""
            INSERT INTO ticket.tickets
                (ticket_id, display_id, requester_id, title, initial_description, source, application_code,
                 priority, status, current_resolution_cycle_id, created_at, updated_at, version,
                 created_by_type, created_by_id)
            VALUES (?, ?, 'user-constraint-test', 'Constraint test ticket', 'Constraint test description',
                    'PORTAL', 'OTHER', 'UNASSIGNED', ?, ?, ?, ?, 0, 'EMPLOYEE', 'user-constraint-test')
            """, ticketId, "INC-" + System.nanoTime(), status, UUID.randomUUID(), now, now);
    }

    private void insertActiveResolutionCycle(UUID resolutionCycleId, UUID ticketId, int cycleNumber) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("""
            INSERT INTO ticket.ticket_resolution_cycles
                (resolution_cycle_id, ticket_id, cycle_number, cycle_status, opened_at)
            VALUES (?, ?, ?, 'ACTIVE', ?)
            """, resolutionCycleId, ticketId, cycleNumber, now);
    }
}
