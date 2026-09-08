package dev.opsmind.ticketworkflow.ticket.application.service;

import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketTraceQueryPort;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("unit")
class GetTicketTraceApplicationServiceTest {

    private final TicketTraceQueryPort port = mock(TicketTraceQueryPort.class);
    private final GetTicketTraceApplicationService service = new GetTicketTraceApplicationService(port);
    private final TicketId ticketId = TicketId.of(UUID.randomUUID());

    private static ActorContext support(Set<String> scopes) {
        return new ActorContext("IT_SUPPORT", "support-1", "support-console", scopes);
    }

    @Test
    void returnsTheLatestTraceIdForAnAuthorizedSupportActor() {
        when(port.latestTraceIdForTicket(ticketId)).thenReturn(Optional.of("abc123"));

        assertThat(service.latestTraceId(ticketId, support(Set.of("tickets:read:queue", "tickets:timeline:internal"))))
            .contains("abc123");
    }

    @Test
    void rejectsASupportActorWithoutTheInternalScope() {
        assertThatThrownBy(() -> service.latestTraceId(ticketId, support(Set.of("tickets:read:queue"))))
            .isInstanceOf(TicketAuthorizationException.class);
    }

    @Test
    void rejectsANonSupportActorEvenWithTheScope() {
        ActorContext employee = new ActorContext("EMPLOYEE", "emp-1", "portal", Set.of("tickets:timeline:internal"));
        assertThatThrownBy(() -> service.latestTraceId(ticketId, employee))
            .isInstanceOf(TicketAuthorizationException.class);
    }
}
