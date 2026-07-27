package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties;
import dev.opsmind.ticketworkflow.support.ListRequesterTicketsFixtures;
import dev.opsmind.ticketworkflow.ticket.application.command.ActorContext;
import dev.opsmind.ticketworkflow.ticket.application.cursor.TicketListCursorCodec;
import dev.opsmind.ticketworkflow.ticket.application.cursor.TicketListCursorSigner;
import dev.opsmind.ticketworkflow.ticket.application.exception.InvalidCursorException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.RequesterTicketQueryPort;
import dev.opsmind.ticketworkflow.ticket.application.query.ListRequesterTicketsQuery;
import dev.opsmind.ticketworkflow.ticket.application.query.RequesterTicketListResult;
import dev.opsmind.ticketworkflow.ticket.application.query.RequesterTicketSummary;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketListCursor;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketListFilters;
import dev.opsmind.ticketworkflow.ticket.application.service.ListRequesterTicketsApplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
class ListRequesterTicketsApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-25T18:00:00Z");

    private RequesterTicketQueryPort queryPort;
    private TicketListCursorCodec cursorCodec;
    private TicketTelemetry telemetry;
    private ClockPort clock;
    private ListRequesterTicketsApplicationService service;

    @BeforeEach
    void setUp() {
        queryPort = mock(RequesterTicketQueryPort.class);
        telemetry = mock(TicketTelemetry.class);
        clock = mock(ClockPort.class);
        when(clock.now()).thenReturn(NOW);

        TicketWorkflowProperties properties = new TicketWorkflowProperties(
            "unit-test-secret", "unit-test-cursor-secret",
            new TicketWorkflowProperties.Sla("DEFAULT", Duration.ofHours(4), Duration.ofHours(24))
        );
        cursorCodec = new TicketListCursorCodec(new ObjectMapper().findAndRegisterModules(), new TicketListCursorSigner(properties));

        service = new ListRequesterTicketsApplicationService(queryPort, cursorCodec, telemetry, clock);
    }

    private ActorContext employee(String subject) {
        return ListRequesterTicketsFixtures.employeeActor(subject);
    }

    @Test
    void shouldReturnFirstPageWithHasMoreAndDecodableNextCursorWhenMoreRowsExist() {
        List<RequesterTicketSummary> twentyOneRows = java.util.stream.IntStream.range(0, 21)
            .mapToObj(i -> ListRequesterTicketsFixtures.summary(UUID.randomUUID(), NOW.minusSeconds(i)))
            .toList();
        when(queryPort.listForRequester(eq("employee-123"), any(), isNull(), isNull(), eq(21)))
            .thenReturn(twentyOneRows);

        ListRequesterTicketsQuery query = new ListRequesterTicketsQuery(employee("employee-123"), TicketListFilters.none(), 20, null);
        RequesterTicketListResult result = service.list(query);

        assertThat(result.items()).hasSize(20);
        assertThat(result.hasMore()).isTrue();
        assertThat(result.nextCursor()).isNotNull();

        TicketListCursor decoded = cursorCodec.decode(result.nextCursor(), NOW);
        RequesterTicketSummary last = result.items().get(19);
        assertThat(decoded.lastCreatedAt()).isEqualTo(last.createdAt());
        assertThat(decoded.lastTicketId()).isEqualTo(last.ticketId());
        assertThat(decoded.principalSubject()).isEqualTo("employee-123");
        assertThat(decoded.sort()).isEqualTo(TicketListCursor.SORT);
        assertThat(decoded.operation()).isEqualTo(TicketListCursor.OPERATION);
    }

    @Test
    void shouldReturnEmptyListWithNoCursorWhenNoTicketsExist() {
        when(queryPort.listForRequester(any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        ListRequesterTicketsQuery query = new ListRequesterTicketsQuery(employee("employee-123"), TicketListFilters.none(), 20, null);
        RequesterTicketListResult result = service.list(query);

        assertThat(result.items()).isEmpty();
        assertThat(result.hasMore()).isFalse();
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    void shouldQueryUsingAuthenticatedSubjectAsRequesterId() {
        when(queryPort.listForRequester(any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        service.list(new ListRequesterTicketsQuery(employee("employee-123"), TicketListFilters.none(), 20, null));

        verify(queryPort).listForRequester(eq("employee-123"), any(), isNull(), isNull(), eq(21));
    }

    @Test
    void shouldRejectEmployeeMissingReadScope() {
        ListRequesterTicketsQuery query = new ListRequesterTicketsQuery(
            ListRequesterTicketsFixtures.employeeActorWithoutReadScope("employee-123"), TicketListFilters.none(), 20, null
        );

        assertThatThrownBy(() -> service.list(query)).isInstanceOf(TicketAuthorizationException.class);
        verify(queryPort, never()).listForRequester(any(), any(), any(), any(), anyInt());
        verify(telemetry).recordListAuthorizationDenied();
    }

    @Test
    void shouldDecodeCursorAndPassItsBoundaryToTheQueryPort() {
        UUID lastTicketId = UUID.randomUUID();
        Instant lastCreatedAt = NOW.minusSeconds(7200);
        TicketListFilters filters = TicketListFilters.none();
        String cursorToken = cursorCodec.encode(new TicketListCursor(
            TicketListCursor.CURRENT_VERSION, lastCreatedAt, lastTicketId, filters.fingerprint(),
            TicketListCursor.SORT, "employee-123", TicketListCursor.OPERATION, NOW.minusSeconds(60), NOW.plusSeconds(60)
        ));
        when(queryPort.listForRequester(any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        service.list(new ListRequesterTicketsQuery(employee("employee-123"), filters, 20, cursorToken));

        verify(queryPort).listForRequester(eq("employee-123"), eq(filters), eq(lastCreatedAt), eq(lastTicketId), eq(21));
    }

    @Test
    void shouldRejectCursorReusedWithDifferentFilters() {
        TicketListFilters originalFilters = TicketListFilters.none();
        String cursorToken = cursorCodec.encode(new TicketListCursor(
            TicketListCursor.CURRENT_VERSION, NOW.minusSeconds(3600), UUID.randomUUID(), originalFilters.fingerprint(),
            TicketListCursor.SORT, "employee-123", TicketListCursor.OPERATION, NOW.minusSeconds(60), NOW.plusSeconds(60)
        ));

        TicketListFilters differentFilters = new TicketListFilters(
            java.util.Set.of(dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus.RESOLVED), java.util.Set.of(), null, null
        );
        ListRequesterTicketsQuery query = new ListRequesterTicketsQuery(employee("employee-123"), differentFilters, 20, cursorToken);

        assertThatThrownBy(() -> service.list(query)).isInstanceOf(InvalidCursorException.class);
        verify(queryPort, never()).listForRequester(any(), any(), any(), any(), anyInt());
        verify(telemetry).recordListInvalidCursor();
    }

    @Test
    void shouldRejectCursorUsedByADifferentEmployee() {
        TicketListFilters filters = TicketListFilters.none();
        String cursorToken = cursorCodec.encode(new TicketListCursor(
            TicketListCursor.CURRENT_VERSION, NOW.minusSeconds(3600), UUID.randomUUID(), filters.fingerprint(),
            TicketListCursor.SORT, "employee-123", TicketListCursor.OPERATION, NOW.minusSeconds(60), NOW.plusSeconds(60)
        ));

        ListRequesterTicketsQuery query = new ListRequesterTicketsQuery(employee("employee-999"), filters, 20, cursorToken);

        assertThatThrownBy(() -> service.list(query)).isInstanceOf(InvalidCursorException.class);
        verify(queryPort, never()).listForRequester(any(), any(), any(), any(), anyInt());
    }

    @Test
    void shouldRejectTamperedCursor() {
        ListRequesterTicketsQuery query = new ListRequesterTicketsQuery(
            employee("employee-123"), TicketListFilters.none(), 20, "tampered.cursor"
        );

        assertThatThrownBy(() -> service.list(query)).isInstanceOf(InvalidCursorException.class);
    }
}
