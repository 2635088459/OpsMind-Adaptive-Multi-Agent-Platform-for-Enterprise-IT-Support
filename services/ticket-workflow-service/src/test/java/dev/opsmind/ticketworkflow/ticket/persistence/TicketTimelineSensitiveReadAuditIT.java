package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractTicketTimelineIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-006 §23: a Support-internal Timeline read appends exactly one
 * required sensitive-read Audit record; ordinary Employee and Support-public
 * reads do not. Reuses the same {@code ticket.audit_records} table and
 * {@code SensitiveReadAuditPort}/{@code SensitiveReadAuditAdapter} as Get
 * Ticket (SPEC-TW-002) rather than a dedicated Timeline audit sink — see
 * {@code SensitiveReadAuditAdapter}, whose {@code action} column is the
 * fixed {@code "TICKET_VIEWED"} constant shared by every caller; the
 * Timeline-specific view is recorded in {@code view_type} instead (a known
 * deviation from the literal {@code action = TICKET_TIMELINE_VIEWED} shown
 * in SPEC-TW-006 §23's example, since the adapter is intentionally shared
 * rather than duplicated per feature).
 */
@Tag("integration")
class TicketTimelineSensitiveReadAuditIT extends AbstractTicketTimelineIT {

    @Test
    void shouldAppendOneSensitiveReadAuditRecordForSupportInternalView() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, DEFAULT_CREATED_AT);
        seedInternalSupportNote(ticketId, "Escalating to Duo team.", DEFAULT_CREATED_AT.plusSeconds(60));

        ResponseEntity<String> response = getTimeline(ticketId, supportToken("support-100", Set.of(DEFAULT_APPLICATION_CODE), true));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT * FROM ticket.audit_records WHERE resource_id = ?", ticketId.toString()
        );
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("audit_type")).isEqualTo("SENSITIVE_READ");
        assertThat(row.get("decision")).isEqualTo("ALLOWED");
        assertThat(row.get("actor_type")).isEqualTo("IT_SUPPORT");
        assertThat(row.get("actor_id")).isEqualTo("support-100");
        assertThat(row.get("resource_type")).isEqualTo("TICKET");
        assertThat(row.get("view_type")).isEqualTo("SUPPORT_INTERNAL_VIEW");
        assertThat(row.get("outcome")).isEqualTo("SUCCESS");
        assertThat(row.get("data_classification")).isEqualTo("SENSITIVE");
    }

    @Test
    void shouldExcludeMessageContentAndCursorFromTheAuditRecord() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, DEFAULT_CREATED_AT);
        seedInternalSupportNote(ticketId, "very secret escalation detail", DEFAULT_CREATED_AT.plusSeconds(60));

        getTimeline(ticketId, supportToken("support-100", Set.of(DEFAULT_APPLICATION_CODE), true));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT * FROM ticket.audit_records WHERE resource_id = ?", ticketId.toString()
        );
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).toString()).doesNotContain("very secret escalation detail");
    }

    @Test
    void shouldNotAppendAuditRecordForOrdinaryEmployeeSelfRead() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, DEFAULT_CREATED_AT);
        seedPublicRequesterMessage(ticketId, "hello", DEFAULT_CREATED_AT.plusSeconds(60));

        ResponseEntity<String> response = getTimeline(ticketId, employeeToken(DEFAULT_REQUESTER));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Integer auditCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.audit_records WHERE resource_id = ?", Integer.class, ticketId.toString()
        );
        assertThat(auditCount).isZero();
    }

    @Test
    void shouldNotAppendAuditRecordForSupportPublicView() {
        UUID ticketId = seedTicket(DEFAULT_REQUESTER, DEFAULT_APPLICATION_CODE, DEFAULT_CREATED_AT);
        seedPublicRequesterMessage(ticketId, "hello", DEFAULT_CREATED_AT.plusSeconds(60));

        ResponseEntity<String> response = getTimeline(ticketId, supportToken("support-100", Set.of(DEFAULT_APPLICATION_CODE)));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Integer auditCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ticket.audit_records WHERE resource_id = ?", Integer.class, ticketId.toString()
        );
        assertThat(auditCount).isZero();
    }
}
