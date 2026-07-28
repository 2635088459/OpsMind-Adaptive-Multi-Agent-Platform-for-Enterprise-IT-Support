package dev.opsmind.ticketworkflow.ticket.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties;
import dev.opsmind.ticketworkflow.support.SupportQueueFixtures;
import dev.opsmind.ticketworkflow.ticket.api.support.SupportQueueApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.support.SupportQueueResponse;
import dev.opsmind.ticketworkflow.ticket.application.event.RequesterPseudonymizer;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-005 §17: the Support Ticket Summary excludes full Description,
 * message/note content, requester email, tool credentials, and full
 * audit metadata — structural, since {@link SupportQueueResponse} has no
 * field to leak them through, plus a check that the serialized JSON
 * stays that way and that the requester identity is pseudonymized.
 */
@Tag("security")
class SupportQueueFieldVisibilityTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void responseShouldExcludeForbiddenFieldsAndPseudonymizeRequester() throws Exception {
        RequesterPseudonymizer pseudonymizer = new RequesterPseudonymizer(new TicketWorkflowProperties(
            "unit-test-secret", "unit-test-cursor-secret",
            new TicketWorkflowProperties.Sla("DEFAULT", Duration.ofHours(4), Duration.ofHours(24), Duration.ofHours(4))
        ));
        SupportQueueApiMapper mapper = new SupportQueueApiMapper(pseudonymizer);
        Instant now = Instant.parse("2026-07-23T16:30:00Z");
        SupportQueueResult result = new SupportQueueResult(
            List.of(SupportQueueFixtures.summary(java.util.UUID.randomUUID(), now)),
            25, false, null, now, SupportQueueFixtures.noFilters()
        );

        SupportQueueResponse response = mapper.toResponse(result);
        String json = objectMapper.writeValueAsString(response);

        assertThat(json).doesNotContain("employee-123");
        assertThat(json).doesNotContain("description");
        assertThat(json).doesNotContain("initialDescription");
        assertThat(json).doesNotContain("message");
        assertThat(json).doesNotContain("internalNote");
        assertThat(json).doesNotContain("email");
        assertThat(json).doesNotContain("password");
        assertThat(json).doesNotContain("token");
        assertThat(json).doesNotContain("credential");
        assertThat(json).doesNotContain("auditId");
        assertThat(json).contains("\"requesterRef\"");
        assertThat(response.items().get(0).requesterRef()).isNotEqualTo("employee-123");
    }
}
