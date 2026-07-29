package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractTriageTicketIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-007 AC-02: a valid category with no {@code subcategoryId} succeeds and leaves the column null. */
@Tag("integration")
class TriageTicketOptionalSubcategoryIT extends AbstractTriageTicketIT {

    @Test
    void shouldTriageSuccessfullyWithoutASubcategoryAndLeaveItNull() {
        UUID ticketId = seedOpenTicket();
        UUID categoryId = seedCategory(true);
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);

        ResponseEntity<String> response = triage(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            triageRequestBody(categoryId, null, "MEDIUM", queueId)
        );

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        // The application-wide Jackson default (spring.jackson.default-property-inclusion:
        // non_null, application.yml) omits null fields entirely rather than emitting
        // "subcategoryId":null, since TriageTicketResponse has no @JsonInclude(ALWAYS) override.
        assertThat(response.getBody()).doesNotContain("subcategoryId");

        Map<String, Object> row = ticketRow(ticketId);
        assertThat(row.get("status")).isEqualTo("TRIAGED");
        assertThat(row.get("subcategory_id")).isNull();
        assertThat(row.get("category_id")).isEqualTo(categoryId);
    }
}
