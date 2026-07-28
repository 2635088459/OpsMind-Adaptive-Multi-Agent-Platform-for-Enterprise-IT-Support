package dev.opsmind.ticketworkflow.ticket.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties;
import dev.opsmind.ticketworkflow.support.GetTicketFixtures;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.EmployeeTicketDetailResponse;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.PublicTicketQueryApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.support.SupportTicketDetailResponse;
import dev.opsmind.ticketworkflow.ticket.api.support.SupportTicketQueryApiMapper;
import dev.opsmind.ticketworkflow.ticket.application.event.RequesterPseudonymizer;
import dev.opsmind.ticketworkflow.ticket.application.query.EmployeeTicketProjection;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportTicketProjection;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-002 §12/§13/§21: Employee schema excludes internal fields, and
 * Support schema excludes secrets. Response types are dedicated records, so
 * this is structural (the Employee record has no field to leak requesterRef
 * through); this test also proves the serialized JSON stays that way.
 */
@Tag("security")
class GetTicketFieldVisibilityTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void employeeResponseShouldExcludeInternalAndSensitiveFields() throws Exception {
        EmployeeTicketProjection projection = GetTicketFixtures.employeeProjection(GetTicketFixtures.DEFAULT_TICKET_ID);
        EmployeeTicketDetailResponse response = new PublicTicketQueryApiMapper().toResponse(projection);

        String json = objectMapper.writeValueAsString(response);

        assertThat(json).doesNotContain("requesterRef");
        assertThat(json).doesNotContain("assignment");
        assertThat(json).doesNotContain("resolutionCycle");
        assertThat(json).doesNotContain("auditId");
        assertThat(json).doesNotContain("riskScore");
        assertThat(json).contains("\"title\"");
        assertThat(json).contains("\"description\"");
    }

    @Test
    void supportResponseShouldPseudonymizeRequesterAndExcludeSecrets() throws Exception {
        SupportTicketProjection projection = GetTicketFixtures.supportProjection(GetTicketFixtures.DEFAULT_TICKET_ID, "employee-123");
        RequesterPseudonymizer pseudonymizer = new RequesterPseudonymizer(new TicketWorkflowProperties(
            "unit-test-secret", "unit-test-cursor-secret",
            new TicketWorkflowProperties.Sla("DEFAULT", Duration.ofHours(4), Duration.ofHours(24), Duration.ofHours(4))
        ));
        SupportTicketDetailResponse response = new SupportTicketQueryApiMapper(pseudonymizer).toResponse(projection);

        String json = objectMapper.writeValueAsString(response);

        assertThat(response.requesterRef()).isNotEqualTo("employee-123");
        assertThat(json).doesNotContain("employee-123");
        assertThat(json).doesNotContain("password");
        assertThat(json).doesNotContain("token");
        assertThat(json).doesNotContain("secret");
    }
}
