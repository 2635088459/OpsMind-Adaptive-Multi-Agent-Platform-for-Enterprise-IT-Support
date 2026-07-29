package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.support.TriageTicketFixtures;
import dev.opsmind.ticketworkflow.ticket.application.command.TriageTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.TriageTicketResult;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketTriagedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationOutcome;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.RequestHashCalculator;
import dev.opsmind.ticketworkflow.ticket.application.model.AuditRecordEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.OutboxEventEntry;
import dev.opsmind.ticketworkflow.ticket.application.model.TicketStatusHistoryEntry;
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
import org.mockito.ArgumentCaptor;

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
 * SPEC-TW-007: the full success transaction and the idempotency
 * reserve/replay/conflict/in-progress outcomes, mirroring {@code
 * AddTicketMessageApplicationServiceTest}/{@code
 * AddTicketMessageIdempotencyReplayTest}'s structure.
 */
@Tag("unit")
class TriageTicketApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-29T18:30:00Z");
    private static final UUID TICKET_ID = TriageTicketFixtures.DEFAULT_TICKET_ID;

    private TicketTriageGuardPort guardPort;
    private TicketCategoryCatalogPort categoryCatalogPort;
    private TicketSubcategoryCatalogPort subcategoryCatalogPort;
    private SupportQueueCatalogPort supportQueueCatalogPort;
    private TicketTriageRepository triageRepository;
    private TicketHistoryWriter historyWriter;
    private AuditRecordPort auditRecordPort;
    private OutboxEventRepository outboxEventRepository;
    private IdempotencyRepository idempotencyRepository;
    private ClockPort clock;
    private TicketTelemetry telemetry;
    private TriageTicketApplicationService service;

    @BeforeEach
    void setUp() {
        guardPort = mock(TicketTriageGuardPort.class);
        categoryCatalogPort = mock(TicketCategoryCatalogPort.class);
        subcategoryCatalogPort = mock(TicketSubcategoryCatalogPort.class);
        supportQueueCatalogPort = mock(SupportQueueCatalogPort.class);
        triageRepository = mock(TicketTriageRepository.class);
        historyWriter = mock(TicketHistoryWriter.class);
        auditRecordPort = mock(AuditRecordPort.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        idempotencyRepository = mock(IdempotencyRepository.class);
        clock = mock(ClockPort.class);
        telemetry = mock(TicketTelemetry.class);

        when(clock.now()).thenReturn(NOW);
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

    private TriageTicketCommand validCommand(String idempotencyKey) {
        return TriageTicketFixtures.command(
            TICKET_ID, TriageTicketFixtures.supportActor("support-100"), Set.of(TriageTicketFixtures.DEFAULT_TEAM_ID), 7L, idempotencyKey
        );
    }

    @Test
    void shouldTriageSuccessfullyAndPersistHistoryAuditOutboxAndIdempotency() {
        TriageTicketResult result = service.triage(validCommand("key-1"));

        assertThat(result.status()).isEqualTo(TicketStatus.TRIAGED);
        assertThat(result.categoryId()).isEqualTo(TriageTicketFixtures.DEFAULT_CATEGORY_ID);
        assertThat(result.subcategoryId()).isEqualTo(TriageTicketFixtures.DEFAULT_SUBCATEGORY_ID);
        assertThat(result.priority()).isEqualTo(TicketPriority.HIGH);
        assertThat(result.supportQueueId()).isEqualTo(TriageTicketFixtures.DEFAULT_SUPPORT_QUEUE_ID);
        assertThat(result.triagedBy()).isEqualTo("support-100");
        assertThat(result.triagedAt()).isEqualTo(NOW);
        assertThat(result.version()).isEqualTo(8L);
        assertThat(result.idempotencyReplayed()).isFalse();

        ArgumentCaptor<TicketStatusHistoryEntry> historyCaptor = ArgumentCaptor.forClass(TicketStatusHistoryEntry.class);
        verify(historyWriter).append(historyCaptor.capture());
        assertThat(historyCaptor.getValue().fromStatus()).isEqualTo(TicketStatus.NEW);
        assertThat(historyCaptor.getValue().toStatus()).isEqualTo(TicketStatus.TRIAGED);
        assertThat(historyCaptor.getValue().transitionId()).isEqualTo("SM-002");
        assertThat(historyCaptor.getValue().reasonCode()).isEqualTo("TICKET_TRIAGED");
        assertThat(historyCaptor.getValue().aggregateVersion()).isEqualTo(8L);

        ArgumentCaptor<AuditRecordEntry> auditCaptor = ArgumentCaptor.forClass(AuditRecordEntry.class);
        verify(auditRecordPort).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("TICKET_TRIAGED");
        assertThat(auditCaptor.getValue().resourceType()).isEqualTo("TICKET");
        assertThat(auditCaptor.getValue().resourceId()).isEqualTo(TICKET_ID.toString());
        assertThat(auditCaptor.getValue().outcome()).isEqualTo("SUCCESS");
        assertThat(auditCaptor.getValue().ticketStatusBefore()).isEqualTo("NEW");
        assertThat(auditCaptor.getValue().ticketStatusAfter()).isEqualTo("TRIAGED");

        ArgumentCaptor<OutboxEventEntry> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntry.class);
        verify(outboxEventRepository).append(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().eventType()).isEqualTo("ticket.triaged");
        assertThat(outboxCaptor.getValue().eventVersion()).isEqualTo("1.0");
        assertThat(outboxCaptor.getValue().routingKey()).isEqualTo("ticket.triaged.v1");
        assertThat(outboxCaptor.getValue().payload()).doesNotContainKey("reason");

        verify(idempotencyRepository).complete(any(), any());
        verify(telemetry).recordTriaged(TicketPriority.HIGH);
    }

    @Test
    void shouldPassTheResolvedQueueTeamIdIntoTheRepositoryUpdate() {
        service.triage(validCommand("key-2"));

        ArgumentCaptor<dev.opsmind.ticketworkflow.ticket.application.port.out.TicketTriageUpdate> updateCaptor =
            ArgumentCaptor.forClass(dev.opsmind.ticketworkflow.ticket.application.port.out.TicketTriageUpdate.class);
        verify(triageRepository).applyTriage(updateCaptor.capture());
        assertThat(updateCaptor.getValue().teamId()).isEqualTo(TriageTicketFixtures.DEFAULT_TEAM_ID);
        assertThat(updateCaptor.getValue().expectedVersion()).isEqualTo(7L);
        assertThat(updateCaptor.getValue().triagedByActorId()).isEqualTo("support-100");
    }

    @Test
    void shouldReturnOriginalResponseOnReplayWithoutAnySideEffects() {
        UUID replayTicketId = TICKET_ID;
        String storedJson = """
            {"ticketId":"%s","status":"TRIAGED","categoryId":"%s","subcategoryId":"%s","priority":"HIGH",\
            "supportQueueId":"%s","triagedBy":"support-100","triagedAt":"2026-07-29T18:00:00Z","version":8}
            """.formatted(
            replayTicketId, TriageTicketFixtures.DEFAULT_CATEGORY_ID.value(), TriageTicketFixtures.DEFAULT_SUBCATEGORY_ID.value(),
            TriageTicketFixtures.DEFAULT_SUPPORT_QUEUE_ID.value()
        );
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.Replayed(200, storedJson));

        TriageTicketResult result = service.triage(validCommand("same-key"));

        assertThat(result.idempotencyReplayed()).isTrue();
        assertThat(result.version()).isEqualTo(8L);
        verify(guardPort, never()).loadGuard(any());
        verify(triageRepository, never()).applyTriage(any());
        verify(historyWriter, never()).append(any());
        verify(auditRecordPort, never()).append(any());
        verify(outboxEventRepository, never()).append(any());
        verify(telemetry).recordTriageReplay();
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithADifferentPayload() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.KeyReused());

        assertThatThrownBy(() -> service.triage(validCommand("same-key"))).isInstanceOf(IdempotencyKeyReusedException.class);
        verify(triageRepository, never()).applyTriage(any());
    }

    @Test
    void shouldRejectAFreshInProgressDuplicate() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.RequestInProgress());

        assertThatThrownBy(() -> service.triage(validCommand("same-key"))).isInstanceOf(RequestInProgressException.class);
        verify(triageRepository, never()).applyTriage(any());
    }
}
