package dev.opsmind.ticketworkflow.ticket.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties;
import dev.opsmind.ticketworkflow.support.TicketTimelineFixtures;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.EmployeeTimelineApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.EmployeeTimelineResponse;
import dev.opsmind.ticketworkflow.ticket.api.support.SupportTimelineApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.support.SupportTimelineResponse;
import dev.opsmind.ticketworkflow.ticket.application.event.RequesterPseudonymizer;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineItem;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineResult;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineViewType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-TW-006 §19: Employee Timeline items structurally cannot carry actor
 * IDs, internal actor references, internal reason codes, workflow IDs, or
 * Audit metadata — {@link dev.opsmind.ticketworkflow.ticket.api.publicapi.EmployeeTimelineItemResponse}
 * has no field to carry them through. Verified both by frozen-schema
 * conformance ({@code additionalProperties: false}) and by walking the
 * serialized JSON tree for forbidden key names, since a schema alone
 * would not catch a forbidden key nested one level differently than
 * expected.
 */
@Tag("unit")
class TicketTimelineResponseRedactionTest {

    private static final UUID TICKET_ID = UUID.randomUUID();
    private static final Instant OCCURRED_AT = Instant.parse("2026-07-25T19:00:00Z");
    private static final Instant SNAPSHOT_AT = Instant.parse("2026-07-25T19:05:00Z");

    private static final Set<String> FORBIDDEN_EMPLOYEE_KEYS = Set.of(
        "actorId", "authorRef", "actorRef", "internalReasonCode", "reasonCode", "transitionId",
        "workflowId", "auditId", "auditType", "dataClassification", "traceId"
    );

    private final ObjectMapper objectMapper = new ObjectMapper()
        .findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    // The Timeline response schemas $ref a sibling item schema by relative filename
    // (e.g. "employee-timeline-item.schema.json"), which resolves against the schema's
    // own https://opsmind.dev $id and would otherwise trigger a live network fetch.
    // Mapping that prefix to the classpath keeps schema resolution offline and hermetic.
    private final JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012, builder -> builder
        .schemaMappers(schemaMappers -> schemaMappers.mapPrefix("https://opsmind.dev/schemas/ticket/", "classpath:schemas/ticket/"))
    );

    private TicketTimelineResult resultWithOnePublicItemPerType() {
        UUID historyId = UUID.randomUUID();
        UUID requesterMessageId = UUID.randomUUID();
        UUID supportMessageId = UUID.randomUUID();
        List<TicketTimelineItem> items = List.of(
            TicketTimelineFixtures.ticketCreatedItem(TICKET_ID, OCCURRED_AT),
            TicketTimelineFixtures.statusChangedItem(historyId, OCCURRED_AT.plusSeconds(60), "NEW", "TRIAGING"),
            TicketTimelineFixtures.publicRequesterMessageItem(requesterMessageId, OCCURRED_AT.plusSeconds(120), "I still cannot sign in."),
            new TicketTimelineItem(
                "MESSAGE:" + supportMessageId, dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineItemType.PUBLIC_SUPPORT_MESSAGE,
                "PUBLIC", OCCURRED_AT.plusSeconds(180), "IT_SUPPORT", "support-100", null, null, null, null,
                "PUBLIC_SUPPORT_MESSAGE", "Looking into this now.", 0L
            )
        );
        return new TicketTimelineResult(TICKET_ID, "INC-2048", TicketTimelineViewType.EMPLOYEE_PUBLIC_VIEW, items, 50, false, null, SNAPSHOT_AT);
    }

    @Test
    void employeeResponseShouldConformToFrozenSchemaForEveryPublicItemType() throws Exception {
        EmployeeTimelineResponse response = new EmployeeTimelineApiMapper().toResponse(resultWithOnePublicItemPerType());

        Set<ValidationMessage> errors = validate("schemas/ticket/employee-timeline-response.schema.json", response);

        assertThat(errors).isEmpty();
    }

    @Test
    void employeeResponseJsonShouldNeverContainForbiddenKeysAtAnyDepth() throws Exception {
        EmployeeTimelineResponse response = new EmployeeTimelineApiMapper().toResponse(resultWithOnePublicItemPerType());

        JsonNode tree = objectMapper.valueToTree(response);
        Set<String> foundForbiddenKeys = new HashSet<>();
        collectForbiddenKeys(tree, FORBIDDEN_EMPLOYEE_KEYS, foundForbiddenKeys);

        assertThat(foundForbiddenKeys).isEmpty();
    }

    @Test
    void employeeResponseShouldNeverIncludeAnInternalSupportNote() {
        TicketTimelineResult resultWithoutInternalNote = resultWithOnePublicItemPerType();

        // The Employee mapper has no code path that renders INTERNAL_SUPPORT_NOTE (it throws instead of
        // silently dropping it), so proving the mapper never receives one relies on the query-layer SQL
        // predicate (verified by TicketTimelinePublicOnlyQueryIT), not on this mapper alone.
        EmployeeTimelineResponse response = new EmployeeTimelineApiMapper().toResponse(resultWithoutInternalNote);
        assertThat(response.items())
            .extracting(dev.opsmind.ticketworkflow.ticket.api.publicapi.EmployeeTimelineItemResponse::itemType)
            .doesNotContain("INTERNAL_SUPPORT_NOTE");
    }

    @Test
    void supportInternalResponseShouldConformToFrozenSchemaAndExcludeJwtOrCredentialKeys() throws Exception {
        RequesterPseudonymizer pseudonymizer = new RequesterPseudonymizer(new TicketWorkflowProperties(
            "unit-test-secret", "unit-test-cursor-secret",
            new TicketWorkflowProperties.Sla("DEFAULT", Duration.ofHours(4), Duration.ofHours(24), Duration.ofHours(4), Duration.ofDays(7))
        ));
        UUID internalNoteId = UUID.randomUUID();
        List<TicketTimelineItem> items = List.of(
            TicketTimelineFixtures.ticketCreatedItem(TICKET_ID, OCCURRED_AT),
            TicketTimelineFixtures.internalSupportNoteItem(internalNoteId, OCCURRED_AT.plusSeconds(60), "Escalating to Duo team.")
        );
        TicketTimelineResult result = new TicketTimelineResult(
            TICKET_ID, "INC-2048", TicketTimelineViewType.SUPPORT_INTERNAL_VIEW, items, 50, false, null, SNAPSHOT_AT
        );

        SupportTimelineResponse response = new SupportTimelineApiMapper(pseudonymizer).toResponse(result);

        Set<ValidationMessage> errors = validate("schemas/ticket/support-timeline-response.schema.json", response);
        assertThat(errors).isEmpty();

        JsonNode tree = objectMapper.valueToTree(response);
        Set<String> foundForbiddenKeys = new HashSet<>();
        collectForbiddenKeys(tree, Set.of("jwt", "token", "password", "secret", "credential"), foundForbiddenKeys);
        assertThat(foundForbiddenKeys).isEmpty();
    }

    private void collectForbiddenKeys(JsonNode node, Set<String> forbiddenKeys, Set<String> found) {
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(fieldName -> {
                if (forbiddenKeys.contains(fieldName)) {
                    found.add(fieldName);
                }
                collectForbiddenKeys(node.get(fieldName), forbiddenKeys, found);
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectForbiddenKeys(child, forbiddenKeys, found));
        }
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
