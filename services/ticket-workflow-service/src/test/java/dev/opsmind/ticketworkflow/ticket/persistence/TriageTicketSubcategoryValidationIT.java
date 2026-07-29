package dev.opsmind.ticketworkflow.ticket.persistence;

import dev.opsmind.ticketworkflow.support.AbstractTriageTicketIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-TW-007 AC-04: missing, inactive, or wrong-parent subcategory is rejected with 422. */
@Tag("integration")
class TriageTicketSubcategoryValidationIT extends AbstractTriageTicketIT {

    @Test
    void shouldReturn422WhenTheSubcategoryDoesNotExist() {
        UUID ticketId = seedOpenTicket();
        UUID categoryId = seedCategory(true);
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);

        ResponseEntity<String> response = triage(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            triageRequestBody(categoryId, UUID.randomUUID(), "HIGH", queueId)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("TRIAGE_SUBCATEGORY_INVALID");
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("NEW");
    }

    @Test
    void shouldReturn422WhenTheSubcategoryIsInactive() {
        UUID ticketId = seedOpenTicket();
        UUID categoryId = seedCategory(true);
        UUID subcategoryId = seedSubcategory(categoryId, false);
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);

        ResponseEntity<String> response = triage(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            triageRequestBody(categoryId, subcategoryId, "HIGH", queueId)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("TRIAGE_SUBCATEGORY_INVALID");
    }

    @Test
    void shouldReturn422WhenTheSubcategoryBelongsToADifferentCategory() {
        UUID ticketId = seedOpenTicket();
        UUID categoryId = seedCategory(true);
        UUID otherCategoryId = seedCategory(true);
        UUID subcategoryId = seedSubcategory(otherCategoryId, true);
        UUID queueId = seedSupportQueue(DEFAULT_TEAM_ID, true);

        ResponseEntity<String> response = triage(
            ticketId, supportToken("support-100", Set.of(DEFAULT_TEAM_ID)), "\"0\"", UUID.randomUUID().toString(),
            triageRequestBody(categoryId, subcategoryId, "HIGH", queueId)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("TRIAGE_SUBCATEGORY_INVALID");
        assertThat(ticketRow(ticketId).get("status")).isEqualTo("NEW");
    }
}
