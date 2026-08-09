package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.support.TriageTicketFixtures;
import dev.opsmind.ticketworkflow.ticket.application.command.TriageTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketTriagedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketVersionConflictException;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationOutcome;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.RequestHashCalculator;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.policy.SupportQueueAuthorizationAuditRecorder;
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
import dev.opsmind.ticketworkflow.ticket.domain.exception.InvalidTicketTransitionException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SPEC-TW-007 AC-08/AC-09/AC-10: the guard-based pre-check (404/412/409)
 * and, independently, {@link TicketTriageUpdateOutcome}'s reclassification
 * of the version/state-guarded {@code UPDATE}'s zero-affected-rows case —
 * the sole concurrency authority (deviation #14). The two paths are
 * exercised separately: this simulates the guard's initial read succeeding
 * (matching version/state) while the repository still reports a conflict,
 * the way a genuine second concurrent writer would.
 */
@Tag("unit")
class TriageTicketStateGuardTest {

    private static final UUID TICKET_ID = TriageTicketFixtures.DEFAULT_TICKET_ID;

    private TicketTriageGuardPort guardPort;
    private TicketTriageRepository triageRepository;
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
        IdempotencyRepository idempotencyRepository = mock(IdempotencyRepository.class);
        ClockPort clock = mock(ClockPort.class);
        telemetry = mock(TicketTelemetry.class);

        when(clock.now()).thenReturn(Instant.parse("2026-07-29T18:30:00Z"));
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Reserved(UUID.randomUUID()));
        when(categoryCatalogPort.findActiveById(any())).thenReturn(Optional.of(TriageTicketFixtures.activeCategory()));
        when(subcategoryCatalogPort.findActiveById(any())).thenReturn(Optional.of(TriageTicketFixtures.activeSubcategory()));
        when(supportQueueCatalogPort.findActiveById(any())).thenReturn(Optional.of(TriageTicketFixtures.activeQueue()));

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new TriageTicketApplicationService(
            guardPort, categoryCatalogPort, subcategoryCatalogPort, supportQueueCatalogPort, triageRepository,
            historyWriter, auditRecordPort, outboxEventRepository, idempotencyRepository, clock,
            new RequestHashCalculator(objectMapper), new TicketTriagedEventMapper(), telemetry, objectMapper,
            mock(SupportQueueAuthorizationAuditRecorder.class)
        );
    }

    private TriageTicketCommand command(long expectedVersion, String idempotencyKey) {
        return TriageTicketFixtures.command(
            TICKET_ID, TriageTicketFixtures.supportActor("support-100"), Set.of(TriageTicketFixtures.DEFAULT_TEAM_ID), expectedVersion, idempotencyKey
        );
    }

    @Test
    void shouldReturn404WhenTheGuardFindsNoTicket() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.triage(command(7L, "key-1"))).isInstanceOf(TicketNotFoundException.class);
        verify(triageRepository, never()).applyTriage(any());
    }

    @Test
    void shouldReturn412WhenTheGuardsVersionDoesNotMatchExpectedVersion() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(TriageTicketFixtures.guard(TICKET_ID, TicketStatus.NEW, 9L)));

        assertThatThrownBy(() -> service.triage(command(7L, "key-2")))
            .isInstanceOfSatisfying(TicketVersionConflictException.class, ex -> assertThat(ex.currentVersion()).isEqualTo(9L));
        verify(telemetry).recordTriageVersionConflict();
        verify(triageRepository, never()).applyTriage(any());
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = "NEW", mode = EnumSource.Mode.EXCLUDE)
    void shouldReturn409WhenTheGuardsStatusIsNotNew(TicketStatus status) {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(TriageTicketFixtures.guard(TICKET_ID, status, 7L)));

        assertThatThrownBy(() -> service.triage(command(7L, "key-" + status)))
            .isInstanceOfSatisfying(InvalidTicketTransitionException.class, ex -> {
                assertThat(ex.currentStatus()).isEqualTo(status);
                assertThat(ex.requiredStatus()).isEqualTo(TicketStatus.NEW);
            });
        verify(telemetry).recordTriageStateRejected();
        verify(triageRepository, never()).applyTriage(any());
    }

    @Test
    void shouldReclassifyAZeroRowUpdateAsTicketMissing() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(TriageTicketFixtures.guard(TICKET_ID, TicketStatus.NEW, 7L)));
        when(triageRepository.applyTriage(any())).thenReturn(new TicketTriageUpdateOutcome.TicketMissing());

        assertThatThrownBy(() -> service.triage(command(7L, "key-3"))).isInstanceOf(TicketNotFoundException.class);
    }

    @Test
    void shouldReclassifyAZeroRowUpdateAsAVersionMismatchEvenWhenTheGuardSawAMatchingVersion() {
        // Simulates a genuine concurrent writer that mutated the row between
        // this command's guard read and its UPDATE attempt.
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(TriageTicketFixtures.guard(TICKET_ID, TicketStatus.NEW, 7L)));
        when(triageRepository.applyTriage(any())).thenReturn(new TicketTriageUpdateOutcome.VersionMismatch(8L));

        assertThatThrownBy(() -> service.triage(command(7L, "key-4")))
            .isInstanceOfSatisfying(TicketVersionConflictException.class, ex -> assertThat(ex.currentVersion()).isEqualTo(8L));
        verify(telemetry).recordTriageVersionConflict();
    }

    @Test
    void shouldReclassifyAZeroRowUpdateAsAnInvalidStateEvenWhenTheGuardSawStatusNew() {
        when(guardPort.loadGuard(any())).thenReturn(Optional.of(TriageTicketFixtures.guard(TICKET_ID, TicketStatus.NEW, 7L)));
        when(triageRepository.applyTriage(any())).thenReturn(new TicketTriageUpdateOutcome.InvalidState(TicketStatus.TRIAGED));

        assertThatThrownBy(() -> service.triage(command(7L, "key-5")))
            .isInstanceOfSatisfying(InvalidTicketTransitionException.class, ex -> {
                assertThat(ex.currentStatus()).isEqualTo(TicketStatus.TRIAGED);
                assertThat(ex.requiredStatus()).isEqualTo(TicketStatus.NEW);
            });
        verify(telemetry).recordTriageStateRejected();
    }
}
