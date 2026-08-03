package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties;
import dev.opsmind.ticketworkflow.support.SupportQueueFixtures;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.cursor.SupportQueueCursorCodec;
import dev.opsmind.ticketworkflow.ticket.application.cursor.SupportQueueCursorSigner;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.policy.TicketViewPolicy;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportQueueQueryPort;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueQuery;
import dev.opsmind.ticketworkflow.ticket.application.service.QuerySupportQueueApplicationService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** SPEC-TW-005 §4: only IT_SUPPORT, IT_ADMIN, and IT_MANAGER may call the Support Queue. */
@Tag("unit")
class SupportQueueRoleTest {

    private static final Instant NOW = Instant.parse("2026-07-25T19:00:00Z");

    private QuerySupportQueueApplicationService serviceFor(SupportQueueQueryPort queryPort) {
        ClockPort clock = mock(ClockPort.class);
        when(clock.now()).thenReturn(NOW);
        TicketWorkflowProperties properties = new TicketWorkflowProperties(
            "unit-test-secret", "unit-test-cursor-secret",
            new TicketWorkflowProperties.Sla("DEFAULT", Duration.ofHours(4), Duration.ofHours(24), Duration.ofHours(4), Duration.ofDays(7))
        );
        SupportQueueCursorCodec codec = new SupportQueueCursorCodec(new ObjectMapper().findAndRegisterModules(), new SupportQueueCursorSigner(properties));
        return new QuerySupportQueueApplicationService(queryPort, codec, new TicketViewPolicy(), mock(TicketTelemetry.class), clock, properties);
    }

    @ParameterizedTest
    @ValueSource(strings = {"IT_SUPPORT", "IT_ADMIN", "IT_MANAGER"})
    void shouldAllowApprovedSupportRoles(String actorType) {
        SupportQueueQueryPort queryPort = mock(SupportQueueQueryPort.class);
        when(queryPort.queryQueue(any(), any(), any(), any(), any(), anyInt())).thenReturn(List.of());
        QuerySupportQueueApplicationService service = serviceFor(queryPort);

        ActorContext actor = new ActorContext(actorType, "support-100", "support-console", Set.of(SupportQueueFixtures.QUEUE_READ_SCOPE));
        SupportQueueQuery query = new SupportQueueQuery(
            actor, SupportQueueFixtures.scope(SupportQueueFixtures.DEFAULT_APPLICATION_CODE), SupportQueueFixtures.noFilters(), 25, null
        );

        assertThat(service.query(query).items()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"EMPLOYEE", "AUDITOR", "UNKNOWN_ACTOR_TYPE"})
    void shouldRejectDisallowedActorTypes(String actorType) {
        SupportQueueQueryPort queryPort = mock(SupportQueueQueryPort.class);
        QuerySupportQueueApplicationService service = serviceFor(queryPort);

        ActorContext actor = new ActorContext(actorType, "some-subject", "some-client", Set.of(SupportQueueFixtures.QUEUE_READ_SCOPE));
        SupportQueueQuery query = new SupportQueueQuery(
            actor, SupportQueueFixtures.scope(SupportQueueFixtures.DEFAULT_APPLICATION_CODE), SupportQueueFixtures.noFilters(), 25, null
        );

        assertThatThrownBy(() -> service.query(query)).isInstanceOf(TicketAuthorizationException.class);
    }
}
