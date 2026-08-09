package dev.opsmind.ticketworkflow.ticket.infrastructure.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import dev.opsmind.ticketworkflow.ticket.application.exception.EventSchemaValidationException;
import dev.opsmind.ticketworkflow.ticket.application.port.out.EventSchemaValidator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates outgoing integration event payloads against their published JSON
 * Schema (Draft 2020-12) before the Outbox insert, per 06-event-contracts §22
 * and 13-package-and-class-design §28.
 */
@Component
public class JsonSchemaEventValidator implements EventSchemaValidator {

    private static final Map<String, String> SCHEMA_LOCATIONS = Map.ofEntries(
        Map.entry("ticket.created:1", "event-schemas/ticket/published/ticket-created-v1.schema.json"),
        Map.entry("ticket.message.added:1", "event-schemas/ticket/published/ticket-message-added-v1.schema.json"),
        Map.entry("ticket.triaged:1", "event-schemas/ticket/published/ticket-triaged-v1.schema.json"),
        Map.entry("ticket.assigned:1", "event-schemas/ticket/published/ticket-assigned-v1.schema.json"),
        Map.entry("ticket.reassigned:1", "event-schemas/ticket/published/ticket-reassigned-v1.schema.json"),
        Map.entry("ticket.unassigned:1", "event-schemas/ticket/published/ticket-unassigned-v1.schema.json"),
        Map.entry("ticket.status.changed:1", "event-schemas/ticket/published/ticket-status-changed-v1.schema.json"),
        Map.entry("ticket.resolved:1", "event-schemas/ticket/published/ticket-resolved-v1.schema.json"),
        Map.entry("ticket.closed:1", "event-schemas/ticket/published/ticket-closed-v1.schema.json"),
        Map.entry("ticket.reopened:1", "event-schemas/ticket/published/ticket-reopened-v1.schema.json"),
        Map.entry("ticket.user-input-requested:1", "event-schemas/ticket/published/ticket-user-input-requested-v1.schema.json"),
        Map.entry("ticket.user-reply-received:1", "event-schemas/ticket/published/ticket-user-reply-received-v1.schema.json"),
        Map.entry("ticket.user-input-resumed:1", "event-schemas/ticket/published/ticket-user-input-resumed-v1.schema.json"),
        Map.entry("ticket.approval-wait-started:1", "event-schemas/ticket/published/ticket-approval-wait-started-v1.schema.json"),
        Map.entry("ticket.approval-granted-applied:1", "event-schemas/ticket/published/ticket-approval-granted-applied-v1.schema.json"),
        Map.entry("ticket.approval-rejected-applied:1", "event-schemas/ticket/published/ticket-approval-rejected-applied-v1.schema.json"),
        Map.entry("ticket.approval-expired-applied:1", "event-schemas/ticket/published/ticket-approval-expired-applied-v1.schema.json"),
        Map.entry("ticket.auto-approval-applied:1", "event-schemas/ticket/published/ticket-auto-approval-applied-v1.schema.json"),
        Map.entry("ticket.tool-execution-completed-applied:1", "event-schemas/ticket/published/ticket-tool-execution-completed-applied-v1.schema.json"),
        Map.entry("ticket.tool-execution-failed-applied:1", "event-schemas/ticket/published/ticket-tool-execution-failed-applied-v1.schema.json"),
        Map.entry("ticket.tool-result-unknown-recorded:1", "event-schemas/ticket/published/ticket-tool-result-unknown-recorded-v1.schema.json"),
        Map.entry("ticket.verification-started:1", "event-schemas/ticket/published/ticket-verification-started-v1.schema.json"),
        Map.entry("ticket.verification-success-applied:1", "event-schemas/ticket/published/ticket-verification-success-applied-v1.schema.json"),
        Map.entry("ticket.verification-failure-applied:1", "event-schemas/ticket/published/ticket-verification-failure-applied-v1.schema.json"),
        Map.entry("ticket.resolved-with-verification:1", "event-schemas/ticket/published/ticket-resolved-with-verification-v1.schema.json"),
        Map.entry("ticket.resolution-confirmed:1", "event-schemas/ticket/published/ticket-resolution-confirmed-v1.schema.json"),
        Map.entry("ticket.auto-closed:1", "event-schemas/ticket/published/ticket-auto-closed-v1.schema.json"),
        Map.entry("ticket.cancelled:1", "event-schemas/ticket/published/ticket-cancelled-v1.schema.json"),
        Map.entry("ticket.assignment-updated:1", "event-schemas/ticket/published/ticket-assignment-updated-v1.schema.json"),
        Map.entry("ticket.escalated:1", "event-schemas/ticket/published/ticket-escalated-v1.schema.json"),
        Map.entry("ticket.escalation-resumed:1", "event-schemas/ticket/published/ticket-escalation-resumed-v1.schema.json"),
        Map.entry("ticket.reconciliation-case-opened:1", "event-schemas/ticket/published/ticket-reconciliation-case-opened-v1.schema.json")
    );

    private final JsonSchemaFactory schemaFactory;
    private final ObjectMapper validationObjectMapper;
    private final Map<String, JsonSchema> schemaCache = new ConcurrentHashMap<>();

    /**
     * SPEC-TW-007 introduced this codebase's first published-event payload
     * field that is legitimately {@code null} rather than absent
     * ({@code subcategoryId}). The injected {@code ObjectMapper} bean is
     * configured application-wide with {@code
     * spring.jackson.default-property-inclusion: non_null}
     * (application.yml), which also governs {@link ObjectMapper#valueToTree}
     * for a raw {@code Map} — a null-valued entry is silently dropped
     * instead of becoming a JSON {@code null}. Since every published schema
     * lists its nullable-but-required fields (like {@code subcategoryId})
     * in {@code required}, a dropped key fails "required" validation for
     * every legitimately-null value, not just a genuinely missing one. A
     * dedicated {@code Include.ALWAYS} copy is used only for this
     * validation step so the validator sees the payload's true shape —
     * matching what {@code OutboxPersistenceAdapter} actually persists to
     * the JSONB column (Hibernate's own JSON type mapper does not share
     * Spring's MVC-facing ObjectMapper configuration) — without changing
     * the shared bean's behavior for HTTP responses.
     */
    public JsonSchemaEventValidator(ObjectMapper objectMapper) {
        this.validationObjectMapper = objectMapper.copy().setDefaultPropertyInclusion(JsonInclude.Include.ALWAYS);
        this.schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    }

    @Override
    public void validate(String eventType, String eventVersion, Map<String, Object> payload) {
        String majorVersion = eventVersion.split("\\.")[0];
        String key = eventType + ":" + majorVersion;
        String location = SCHEMA_LOCATIONS.get(key);
        if (location == null) {
            throw new EventSchemaValidationException(eventType, eventVersion, "no schema registered for " + key);
        }

        JsonSchema schema = schemaCache.computeIfAbsent(location, this::loadSchema);
        JsonNode payloadNode = validationObjectMapper.valueToTree(payload);
        Set<ValidationMessage> errors = schema.validate(payloadNode);
        if (!errors.isEmpty()) {
            throw new EventSchemaValidationException(eventType, eventVersion, errors.toString());
        }
    }

    private JsonSchema loadSchema(String location) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(location)) {
            if (in == null) {
                throw new IllegalStateException("event schema not found on classpath: " + location);
            }
            return schemaFactory.getSchema(in);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load event schema: " + location, e);
        }
    }
}
