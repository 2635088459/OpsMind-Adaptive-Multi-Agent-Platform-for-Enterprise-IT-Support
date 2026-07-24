package dev.opsmind.ticketworkflow.ticket.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.support.AbstractCreateTicketIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class CreateTicketIdempotencyIT extends AbstractCreateTicketIT {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldReplaySameResultForSameKeyAndSamePayload() throws Exception {
        String idempotencyKey = newIdempotencyKey();
        String body = validRequestBody();

        ResponseEntity<String> first = createTicket("user-idem-1", idempotencyKey, body);
        ResponseEntity<String> second = createTicket("user-idem-1", idempotencyKey, body);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");

        JsonNode firstBody = objectMapper.readTree(first.getBody());
        JsonNode secondBody = objectMapper.readTree(second.getBody());
        assertThat(secondBody.get("ticketId").asText()).isEqualTo(firstBody.get("ticketId").asText());
        assertThat(second.getHeaders().getLocation()).isEqualTo(first.getHeaders().getLocation());
        assertThat(second.getHeaders().getETag()).isEqualTo(first.getHeaders().getETag());

        assertThat(countRows("ticket.tickets")).isEqualTo(1);
    }

    @Test
    void shouldRejectSameKeyWithDifferentPayload() {
        String idempotencyKey = newIdempotencyKey();

        ResponseEntity<String> first = createTicket("user-idem-2", idempotencyKey, validRequestBody());
        ResponseEntity<String> second = createTicket(
            "user-idem-2", idempotencyKey, validRequestBody("A different title entirely", "A different description entirely.")
        );

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("IDEMPOTENCY_KEY_REUSED");
    }
}
