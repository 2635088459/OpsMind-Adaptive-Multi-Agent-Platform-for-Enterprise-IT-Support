package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractGetTicketIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-002 §16: Support sensitive reads append exactly one audit record; Employee self-reads do not. */
@Tag("integration")
class GetTicketSensitiveReadAuditIT extends AbstractGetTicketIT {

    @Test
    void shouldAppendOneSensitiveReadAuditRecordForSupportView() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, "HOUSING_PORTAL");

        ResponseEntity<String> response = getTicket(ticketId, supportToken("support-100", List.of("HOUSING_PORTAL")));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT * FROM ticket.audit_records WHERE resource_id = ?", ticketId.toString()
        );
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("audit_type")).isEqualTo("SENSITIVE_READ");
        assertThat(row.get("action")).isEqualTo("TICKET_VIEWED");
        assertThat(row.get("decision")).isEqualTo("ALLOWED");
        assertThat(row.get("actor_type")).isEqualTo("IT_SUPPORT");
        assertThat(row.get("actor_id")).isEqualTo("support-100");
        assertThat(row.get("resource_type")).isEqualTo("TICKET");
        assertThat(row.get("view_type")).isEqualTo("SUPPORT_VIEW");
        assertThat(row.get("outcome")).isEqualTo("SUCCESS");
        assertThat(row.get("data_classification")).isEqualTo("SENSITIVE");
    }

    @Test
    void shouldNotAppendAuditRecordForOrdinaryEmployeeSelfRead() {
        UUID ticketId = seedTicket();

        ResponseEntity<String> response = getTicket(ticketId, employeeToken(DEFAULT_REQUESTER));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Integer auditCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.audit_records WHERE resource_id = ?", Integer.class, ticketId.toString()
        );
        assertThat(auditCount).isZero();
    }

    @Test
    void shouldExcludeTitleAndDescriptionFromTheAuditRecord() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, "HOUSING_PORTAL");
        getTicket(ticketId, supportToken("support-100", List.of("HOUSING_PORTAL")));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT * FROM ticket.audit_records WHERE resource_id = ?", ticketId.toString()
        );
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).toString()).doesNotContain("Cannot sign in to Housing Portal");
        assertThat(rows.get(0).toString()).doesNotContain("Duo keeps asking me to enroll again.");
    }
}
