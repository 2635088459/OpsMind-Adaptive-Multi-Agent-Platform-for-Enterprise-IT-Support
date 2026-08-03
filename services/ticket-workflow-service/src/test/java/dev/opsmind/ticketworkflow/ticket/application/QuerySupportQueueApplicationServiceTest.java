package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties;
import dev.opsmind.ticketworkflow.support.SupportQueueFixtures;
import dev.opsmind.ticketworkflow.ticket.application.cursor.SupportQueueCursorCodec;
import dev.opsmind.ticketworkflow.ticket.application.cursor.SupportQueueCursorSigner;
import dev.opsmind.ticketworkflow.ticket.application.exception.FilterOutsideAuthorizedScopeException;
import dev.opsmind.ticketworkflow.ticket.application.exception.InvalidCursorException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.policy.TicketViewPolicy;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportQueueQueryPort;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueCursor;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueFilters;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueQuery;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueResult;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueScope;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueSortVersion;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportTicketSummary;
import dev.opsmind.ticketworkflow.ticket.application.service.QuerySupportQueueApplicationService;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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
class QuerySupportQueueApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-25T19:00:00Z");

    private SupportQueueQueryPort queryPort;
    private SupportQueueCursorCodec cursorCodec;
    private TicketTelemetry telemetry;
    private ClockPort clock;
    private QuerySupportQueueApplicationService service;

    @BeforeEach
    void setUp() {
        queryPort = mock(SupportQueueQueryPort.class);
        telemetry = mock(TicketTelemetry.class);
        clock = mock(ClockPort.class);
        when(clock.now()).thenReturn(NOW);

        TicketWorkflowProperties properties = new TicketWorkflowProperties(
            "unit-test-secret", "unit-test-cursor-secret",
            new TicketWorkflowProperties.Sla("DEFAULT", Duration.ofHours(4), Duration.ofHours(24), Duration.ofHours(4), Duration.ofDays(7))
        );
        cursorCodec = new SupportQueueCursorCodec(new ObjectMapper().findAndRegisterModules(), new SupportQueueCursorSigner(properties));

        service = new QuerySupportQueueApplicationService(queryPort, cursorCodec, new TicketViewPolicy(), telemetry, clock, properties);
    }

    private SupportQueueScope scope() {
        return SupportQueueFixtures.scope(SupportQueueFixtures.DEFAULT_APPLICATION_CODE);
    }

    @Test
    void shouldReturnFirstPageWithHasMoreAndDecodableNextCursorWhenMoreRowsExist() {
        List<SupportTicketSummary> twentySixRows = java.util.stream.IntStream.range(0, 26)
            .mapToObj(i -> SupportQueueFixtures.summary(UUID.randomUUID(), NOW.minusSeconds(i)))
            .toList();
        when(queryPort.queryQueue(any(), any(), eq(NOW), any(), isNull(), eq(26))).thenReturn(twentySixRows);

        SupportQueueQuery query = new SupportQueueQuery(
            SupportQueueFixtures.supportActor("support-100"), scope(), SupportQueueFixtures.noFilters(), 25, null
        );
        SupportQueueResult result = service.query(query);

        assertThat(result.items()).hasSize(25);
        assertThat(result.hasMore()).isTrue();
        assertThat(result.nextCursor()).isNotNull();
        assertThat(result.evaluationTime()).isEqualTo(NOW);

        SupportQueueCursor decoded = cursorCodec.decode(result.nextCursor(), NOW);
        SupportTicketSummary last = result.items().get(24);
        assertThat(decoded.lastCreatedAt()).isEqualTo(last.createdAt());
        assertThat(decoded.lastTicketId()).isEqualTo(last.ticketId());
        assertThat(decoded.evaluationTime()).isEqualTo(NOW);
        assertThat(decoded.principalSubject()).isEqualTo("support-100");
        assertThat(decoded.operation()).isEqualTo(SupportQueueCursor.OPERATION);
        assertThat(decoded.sortVersion()).isEqualTo(SupportQueueSortVersion.CURRENT_VERSION);
    }

    @Test
    void shouldReturnEmptyListWithNoCursorWhenNoTicketsExist() {
        when(queryPort.queryQueue(any(), any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        SupportQueueQuery query = new SupportQueueQuery(
            SupportQueueFixtures.supportActor("support-100"), scope(), SupportQueueFixtures.noFilters(), 25, null
        );
        SupportQueueResult result = service.query(query);

        assertThat(result.items()).isEmpty();
        assertThat(result.hasMore()).isFalse();
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    void shouldRejectSupportActorMissingQueueScope() {
        SupportQueueQuery query = new SupportQueueQuery(
            SupportQueueFixtures.supportActorWithoutScope("support-100"), scope(), SupportQueueFixtures.noFilters(), 25, null
        );

        assertThatThrownBy(() -> service.query(query)).isInstanceOf(TicketAuthorizationException.class);
        verify(queryPort, never()).queryQueue(any(), any(), any(), any(), any(), anyInt());
        verify(telemetry).recordSupportQueueAuthorizationDenied();
    }

    @Test
    void shouldRejectEmployeeActor() {
        SupportQueueQuery query = new SupportQueueQuery(
            SupportQueueFixtures.employeeActor("employee-123"), scope(), SupportQueueFixtures.noFilters(), 25, null
        );

        assertThatThrownBy(() -> service.query(query)).isInstanceOf(TicketAuthorizationException.class);
        verify(queryPort, never()).queryQueue(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void shouldRejectApplicationCodeFilterOutsideAuthorizedScope() {
        SupportQueueFilters filters = new SupportQueueFilters(
            Set.of(), Set.of(), Set.of(ApplicationCode.VPN), Set.of(), null, false, Set.of(), null, null
        );
        SupportQueueQuery query = new SupportQueueQuery(SupportQueueFixtures.supportActor("support-100"), scope(), filters, 25, null);

        assertThatThrownBy(() -> service.query(query)).isInstanceOf(FilterOutsideAuthorizedScopeException.class);
        verify(queryPort, never()).queryQueue(any(), any(), any(), any(), any(), anyInt());
        verify(telemetry).recordSupportQueueFilterOutsideScope();
    }

    @Test
    void shouldRejectAssignedTeamFilterOutsideAuthorizedScope() {
        SupportQueueFilters filters = new SupportQueueFilters(
            Set.of(), Set.of(), Set.of(), Set.of("TEAM-OTHER"), null, false, Set.of(), null, null
        );
        SupportQueueQuery query = new SupportQueueQuery(SupportQueueFixtures.supportActor("support-100"), scope(), filters, 25, null);

        assertThatThrownBy(() -> service.query(query)).isInstanceOf(FilterOutsideAuthorizedScopeException.class);
        verify(queryPort, never()).queryQueue(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void shouldRejectAssignedAgentFilterForAnotherAgentWhenActorIsPlainSupport() {
        SupportQueueFilters filters = new SupportQueueFilters(
            Set.of(), Set.of(), Set.of(), Set.of(), "agent-999", false, Set.of(), null, null
        );
        SupportQueueQuery query = new SupportQueueQuery(SupportQueueFixtures.supportActor("support-100"), scope(), filters, 25, null);

        assertThatThrownBy(() -> service.query(query)).isInstanceOf(FilterOutsideAuthorizedScopeException.class);
    }

    @Test
    void shouldAllowAssignedAgentFilterForSelf() {
        when(queryPort.queryQueue(any(), any(), any(), any(), any(), anyInt())).thenReturn(List.of());
        SupportQueueFilters filters = new SupportQueueFilters(
            Set.of(), Set.of(), Set.of(), Set.of(), "support-100", false, Set.of(), null, null
        );
        SupportQueueQuery query = new SupportQueueQuery(SupportQueueFixtures.supportActor("support-100"), scope(), filters, 25, null);

        SupportQueueResult result = service.query(query);

        assertThat(result.items()).isEmpty();
    }

    @Test
    void shouldAllowAssignedAgentFilterForAnyAgentWhenActorIsAdmin() {
        when(queryPort.queryQueue(any(), any(), any(), any(), any(), anyInt())).thenReturn(List.of());
        SupportQueueFilters filters = new SupportQueueFilters(
            Set.of(), Set.of(), Set.of(), Set.of(), "agent-999", false, Set.of(), null, null
        );
        SupportQueueQuery query = new SupportQueueQuery(SupportQueueFixtures.adminActor("admin-1"), scope(), filters, 25, null);

        SupportQueueResult result = service.query(query);

        assertThat(result.items()).isEmpty();
    }

    @Test
    void shouldDecodeCursorAndPassItsBoundaryAndFixedEvaluationTimeToTheQueryPort() {
        Instant firstPageEvaluationTime = NOW.minusSeconds(3600);
        UUID lastTicketId = UUID.randomUUID();
        Instant lastCreatedAt = NOW.minusSeconds(7200);
        SupportQueueFilters filters = SupportQueueFixtures.noFilters();
        String cursorToken = cursorCodec.encode(new SupportQueueCursor(
            SupportQueueCursor.CURRENT_VERSION, firstPageEvaluationTime, 1, 2, lastCreatedAt, lastTicketId,
            filters.fingerprint(), scope().fingerprint(), "support-100", SupportQueueSortVersion.CURRENT_VERSION,
            SupportQueueCursor.OPERATION, NOW.minusSeconds(60), NOW.plusSeconds(60)
        ));
        when(queryPort.queryQueue(any(), any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        SupportQueueQuery query = new SupportQueueQuery(SupportQueueFixtures.supportActor("support-100"), scope(), filters, 25, cursorToken);
        SupportQueueResult result = service.query(query);

        assertThat(result.evaluationTime()).isEqualTo(firstPageEvaluationTime);
        verify(queryPort).queryQueue(eq(scope()), eq(filters), eq(firstPageEvaluationTime), any(), any(), eq(26));
    }

    @Test
    void shouldRejectCursorReusedWithDifferentFilters() {
        SupportQueueFilters originalFilters = SupportQueueFixtures.noFilters();
        String cursorToken = cursorCodec.encode(new SupportQueueCursor(
            SupportQueueCursor.CURRENT_VERSION, NOW, 0, 0, NOW.minusSeconds(3600), UUID.randomUUID(),
            originalFilters.fingerprint(), scope().fingerprint(), "support-100", SupportQueueSortVersion.CURRENT_VERSION,
            SupportQueueCursor.OPERATION, NOW.minusSeconds(60), NOW.plusSeconds(60)
        ));

        SupportQueueFilters differentFilters = new SupportQueueFilters(
            Set.of(), Set.of(), Set.of(), Set.of(), null, true, Set.of(), null, null
        );
        SupportQueueQuery query = new SupportQueueQuery(SupportQueueFixtures.supportActor("support-100"), scope(), differentFilters, 25, cursorToken);

        assertThatThrownBy(() -> service.query(query)).isInstanceOf(InvalidCursorException.class);
        verify(queryPort, never()).queryQueue(any(), any(), any(), any(), any(), anyInt());
        verify(telemetry).recordSupportQueueInvalidCursor();
    }

    @Test
    void shouldRejectCursorAfterScopeChanges() {
        SupportQueueFilters filters = SupportQueueFixtures.noFilters();
        String cursorToken = cursorCodec.encode(new SupportQueueCursor(
            SupportQueueCursor.CURRENT_VERSION, NOW, 0, 0, NOW.minusSeconds(3600), UUID.randomUUID(),
            filters.fingerprint(), scope().fingerprint(), "support-100", SupportQueueSortVersion.CURRENT_VERSION,
            SupportQueueCursor.OPERATION, NOW.minusSeconds(60), NOW.plusSeconds(60)
        ));

        SupportQueueScope changedScope = SupportQueueFixtures.scope(SupportQueueFixtures.DEFAULT_APPLICATION_CODE, "EMAIL");
        SupportQueueQuery query = new SupportQueueQuery(SupportQueueFixtures.supportActor("support-100"), changedScope, filters, 25, cursorToken);

        assertThatThrownBy(() -> service.query(query)).isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void shouldRejectCursorUsedByADifferentActor() {
        SupportQueueFilters filters = SupportQueueFixtures.noFilters();
        String cursorToken = cursorCodec.encode(new SupportQueueCursor(
            SupportQueueCursor.CURRENT_VERSION, NOW, 0, 0, NOW.minusSeconds(3600), UUID.randomUUID(),
            filters.fingerprint(), scope().fingerprint(), "support-100", SupportQueueSortVersion.CURRENT_VERSION,
            SupportQueueCursor.OPERATION, NOW.minusSeconds(60), NOW.plusSeconds(60)
        ));

        SupportQueueQuery query = new SupportQueueQuery(SupportQueueFixtures.supportActor("support-999"), scope(), filters, 25, cursorToken);

        assertThatThrownBy(() -> service.query(query)).isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void shouldRejectTamperedCursor() {
        SupportQueueQuery query = new SupportQueueQuery(
            SupportQueueFixtures.supportActor("support-100"), scope(), SupportQueueFixtures.noFilters(), 25, "tampered.cursor"
        );

        assertThatThrownBy(() -> service.query(query)).isInstanceOf(InvalidCursorException.class);
    }
}
