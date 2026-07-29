package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.support.TriageTicketFixtures;
import dev.opsmind.ticketworkflow.ticket.application.command.TriageTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketTriagedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.QueueAccessDeniedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TriageNotAllowedException;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationOutcome;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.RequestHashCalculator;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SupportQueueCatalogPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketCategoryCatalogPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketSubcategoryCatalogPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketTriageGuardPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketTriageRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketTriageUpdateOutcome;
import dev.opsmind.ticketworkflow.ticket.application.service.TriageTicketApplicationService;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketPriority;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SPEC-TW-007 AC-07/domain-rules §6: a Requester is always rejected
 * (cheaply, before idempotency reservation); any other actor type missing
 * {@code ticket:triage} or the target queue's team grant is rejected with
 * {@code QueueAccessDeniedException}. There is no actor-type allowlist
 * beyond excluding {@code EMPLOYEE} (deviation #5).
 */
@Tag("unit")
class TriageTicketAuthorizationTest {

    private static final UUID TICKET_ID = TriageTicketFixtures.DEFAULT_TICKET_ID;

    private TicketTriageGuardPort guardPort;
    private TicketTriageRepository triageRepository;
    private IdempotencyRepository idempotencyRepository;
    private TicketTelemetry telemetry;
    private TriageTicketApplicationService service;

    @BeforeEach
    void setUp() {
        guardPort = mock(TicketTriageGuardPort.class);
        TicketCategoryCatalogPort categoryCatalogPort = mock(TicketCategoryCatalogPort.class);
        TicketSubcategoryCatalogPort subcategoryCatalogPort = mock(TicketSubcategoryCatalogPort.class);
        SupportQueueCatalogPort supportQueueCatalogPort = mock(SupportQueueCatalogPort.class);
        triageRepository = mock(TicketTriageRepository.class);
        TicketHistoryWriter historyWriter = mock(TicketHistoryWriter.class);
        AuditRecordPort auditRecordPort = mock(AuditRecordPort.class);
        OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
        idempotencyRepository = mock(IdempotencyRepository.class);
        ClockPort clock = mock(ClockPort.class);
        telemetry = mock(TicketTelemetry.class);

        when(clock.now()).thenReturn(Instant.parse("2026-07-29T18:30:00Z"));
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Reserved(UUID.randomUUID()));
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(TriageTicketFixtures.guard(TICKET_ID, TicketStatus.NEW, 7L)));
        when(categoryCatalogPort.findActiveById(any())).thenReturn(Optional.of(TriageTicketFixtures.activeCategory()));
        when(subcategoryCatalogPort.findActiveById(any())).thenReturn(Optional.of(TriageTicketFixtures.activeSubcategory()));
        when(supportQueueCatalogPort.findActiveById(any())).thenReturn(Optional.of(TriageTicketFixtures.activeQueue()));
        when(triageRepository.applyTriage(any())).thenReturn(new TicketTriageUpdateOutcome.Updated(8L));

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new TriageTicketApplicationService(
            guardPort, categoryCatalogPort, subcategoryCatalogPort, supportQueueCatalogPort, triageRepository,
            historyWriter, auditRecordPort, outboxEventRepository, idempotencyRepository, clock,
            new RequestHashCalculator(objectMapper), new TicketTriagedEventMapper(), telemetry, objectMapper
        );
    }

    @Test
    void shouldAlwaysRejectAnEmployeeActorRegardlessOfScope() {
        TriageTicketCommand command = TriageTicketFixtures.command(
            TICKET_ID, TriageTicketFixtures.employeeActor("employee-123"), Set.of(TriageTicketFixtures.DEFAULT_TEAM_ID), 7L, "key-1"
        );

        assertThatThrownBy(() -> service.triage(command)).isInstanceOf(TriageNotAllowedException.class);
        verify(idempotencyRepository, never()).reserve(any());
        verify(guardPort, never()).loadGuard(any());
        verify(telemetry).recordTriageAuthorizationDenied("role");
    }

    @Test
    void shouldRejectASupportActorMissingTheTriageScope() {
        TriageTicketCommand command = TriageTicketFixtures.command(
            TICKET_ID, TriageTicketFixtures.supportActorWithoutScope("support-100"), Set.of(TriageTicketFixtures.DEFAULT_TEAM_ID), 7L, "key-2"
        );

        assertThatThrownBy(() -> service.triage(command)).isInstanceOf(QueueAccessDeniedException.class);
        verify(idempotencyRepository, never()).reserve(any());
        verify(telemetry).recordTriageAuthorizationDenied("scope");
    }

    @Test
    void shouldRejectAnActorHoldingTheScopeButNotGrantedTheQueuesTeam() {
        TriageTicketCommand command = TriageTicketFixtures.command(
            TICKET_ID, TriageTicketFixtures.supportActor("support-100"), Set.of("SOME-OTHER-TEAM"), 7L, "key-3"
        );

        assertThatThrownBy(() -> service.triage(command)).isInstanceOf(QueueAccessDeniedException.class);
        verify(telemetry).recordTriageAuthorizationDenied("queue_scope");
        verify(triageRepository, never()).applyTriage(any());
    }

    @Test
    void shouldAllowASupportActorGrantedTheQueuesTeam() {
        TriageTicketCommand command = TriageTicketFixtures.command(
            TICKET_ID, TriageTicketFixtures.supportActor("support-100"), Set.of(TriageTicketFixtures.DEFAULT_TEAM_ID), 7L, "key-4"
        );

        assertThatCode(() -> service.triage(command)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(value = TicketPriority.class, names = "UNASSIGNED", mode = EnumSource.Mode.EXCLUDE)
    void shouldAllowEveryNonUnassignedPriorityUnderTheOrdinaryTriageScopeIncludingCritical(TicketPriority priority) {
        // domain-rules §5: ticket:triage:critical has not been introduced
        // anywhere in this codebase (deviation #6), so CRITICAL succeeds
        // under the exact same authorization as any other priority.
        TriageTicketCommand command = TriageTicketFixtures.commandWithPriority(
            TICKET_ID, TriageTicketFixtures.supportActor("support-100"), Set.of(TriageTicketFixtures.DEFAULT_TEAM_ID), 7L, "key-" + priority, priority
        );

        assertThatCode(() -> service.triage(command)).doesNotThrowAnyException();
    }
}
