package dev.opsmind.ticketworkflow.ticket.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties;
import dev.opsmind.ticketworkflow.platform.error.ErrorResponse;
import dev.opsmind.ticketworkflow.support.GetTicketFixtures;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.EmployeeTicketDetailResponse;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.PublicTicketQueryApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.support.SupportTicketDetailResponse;
import dev.opsmind.ticketworkflow.ticket.api.support.SupportTicketQueryApiMapper;
import dev.opsmind.ticketworkflow.ticket.application.event.RequesterPseudonymizer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-002 §21: response bodies conform exactly to the frozen JSON
 * Schema (Draft 2020-12, {@code additionalProperties: false}) — this both
 * proves no extra (potentially leaked) field slips into the JSON and that
 * every schema-required field is actually present, even when its value is
 * {@code null} (e.g. an unassigned Ticket's {@code assignment.teamId}).
 */
@Tag("unit")
class GetTicketResponseRedactionTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
        .findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    @Test
    void employeeResponseShouldConformToFrozenSchema() throws Exception {
        EmployeeTicketDetailResponse response = new PublicTicketQueryApiMapper()
            .toResponse(GetTicketFixtures.employeeProjection(GetTicketFixtures.DEFAULT_TICKET_ID));

        Set<ValidationMessage> errors = validate("schemas/ticket/employee-ticket-response.schema.json", response);

        assertThat(errors).isEmpty();
    }

    @Test
    void supportResponseWithUnassignedFieldsShouldConformToFrozenSchema() throws Exception {
        RequesterPseudonymizer pseudonymizer = new RequesterPseudonymizer(new TicketWorkflowProperties(
            "unit-test-secret", "unit-test-cursor-secret",
            new TicketWorkflowProperties.Sla("DEFAULT", Duration.ofHours(4), Duration.ofHours(24), Duration.ofHours(4), Duration.ofDays(7))
        ));
        SupportTicketDetailResponse response = new SupportTicketQueryApiMapper(pseudonymizer)
            .toResponse(GetTicketFixtures.supportProjection(GetTicketFixtures.DEFAULT_TICKET_ID, "employee-123"));

        Set<ValidationMessage> errors = validate("schemas/ticket/support-ticket-response.schema.json", response);

        assertThat(errors).isEmpty();
    }

    @Test
    void ticketNotFoundErrorShouldConformToFrozenErrorEnvelopeSchema() throws Exception {
        ErrorResponse error = ErrorResponse.of("TICKET_NOT_FOUND", "The Ticket was not found.", "trace-1", "corr-1");

        Set<ValidationMessage> errors = validate("schemas/ticket/error-envelope.schema.json", error);

        assertThat(errors).isEmpty();
    }

    private Set<ValidationMessage> validate(String schemaLocation, Object body) throws Exception {
        JsonSchema schema = loadSchema(schemaLocation);
        JsonNode node = objectMapper.valueToTree(body);
        return schema.validate(node);
    }

    private JsonSchema loadSchema(String location) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(location)) {
            assertThat(in).as("schema resource on classpath: " + location).isNotNull();
            return schemaFactory.getSchema(in);
        }
    }
}
