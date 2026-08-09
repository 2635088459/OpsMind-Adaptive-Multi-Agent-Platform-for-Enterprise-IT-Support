package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.support.TriageTicketFixtures;
import dev.opsmind.ticketworkflow.ticket.application.command.TriageTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketTriagedEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.QueueAccessDeniedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.SupportQueueInvalidException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TriageCategoryInvalidException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TriageSubcategoryInvalidException;
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
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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
 * SPEC-TW-007 AC-03/AC-04/AC-06: category/subcategory/queue existence and
 * active-flag validation, plus AC-04's "wrong parent" subcategory case and
 * the queue-team authorization gate that runs immediately after a valid
 * queue is resolved.
 */
@Tag("unit")
class TriageTicketCatalogValidationTest {

    private static final UUID TICKET_ID = TriageTicketFixtures.DEFAULT_TICKET_ID;

    private TicketCategoryCatalogPort categoryCatalogPort;
    private TicketSubcategoryCatalogPort subcategoryCatalogPort;
    private SupportQueueCatalogPort supportQueueCatalogPort;
    private TicketTriageRepository triageRepository;
    private TicketTelemetry telemetry;
    private TriageTicketApplicationService service;

    @BeforeEach
    void setUp() {
        TicketTriageGuardPort guardPort = mock(TicketTriageGuardPort.class);
        categoryCatalogPort = mock(TicketCategoryCatalogPort.class);
        subcategoryCatalogPort = mock(TicketSubcategoryCatalogPort.class);
        supportQueueCatalogPort = mock(SupportQueueCatalogPort.class);
        triageRepository = mock(TicketTriageRepository.class);
        TicketHistoryWriter historyWriter = mock(TicketHistoryWriter.class);
        AuditRecordPort auditRecordPort = mock(AuditRecordPort.class);
        OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
        IdempotencyRepository idempotencyRepository = mock(IdempotencyRepository.class);
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
            new RequestHashCalculator(objectMapper), new TicketTriagedEventMapper(), telemetry, objectMapper,
            mock(SupportQueueAuthorizationAuditRecorder.class)
        );
    }

    private TriageTicketCommand command(String idempotencyKey) {
        return TriageTicketFixtures.command(
            TICKET_ID, TriageTicketFixtures.supportActor("support-100"), Set.of(TriageTicketFixtures.DEFAULT_TEAM_ID), 7L, idempotencyKey
        );
    }

    @Test
    void shouldRejectAMissingCategory() {
        when(categoryCatalogPort.findActiveById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.triage(command("key-1"))).isInstanceOf(TriageCategoryInvalidException.class);
        verify(telemetry).recordTriageCatalogInvalid("category");
        verify(triageRepository, never()).applyTriage(any());
    }

    /** A missing row and an inactive row are indistinguishable at the port level (deviation #3). */
    @Test
    void shouldTreatAnInactiveCategoryIdenticallyToAMissingOne() {
        when(categoryCatalogPort.findActiveById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.triage(command("key-2"))).isInstanceOf(TriageCategoryInvalidException.class);
    }

    @Test
    void shouldRejectAMissingSubcategory() {
        when(subcategoryCatalogPort.findActiveById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.triage(command("key-3"))).isInstanceOf(TriageSubcategoryInvalidException.class);
        verify(telemetry).recordTriageCatalogInvalid("subcategory");
        verify(triageRepository, never()).applyTriage(any());
    }

    @Test
    void shouldRejectASubcategoryBelongingToADifferentCategory() {
        when(subcategoryCatalogPort.findActiveById(any())).thenReturn(Optional.of(TriageTicketFixtures.subcategoryWithWrongParent()));

        assertThatThrownBy(() -> service.triage(command("key-4"))).isInstanceOf(TriageSubcategoryInvalidException.class);
        verify(telemetry).recordTriageCatalogInvalid("subcategory");
    }

    @Test
    void shouldSkipSubcategoryValidationWhenNoneIsSupplied() {
        TriageTicketCommand withoutSubcategory = new TriageTicketCommand(
            dev.opsmind.ticketworkflow.ticket.domain.value.TicketId.of(TICKET_ID),
            TriageTicketFixtures.DEFAULT_CATEGORY_ID,
            null,
            dev.opsmind.ticketworkflow.ticket.domain.value.TicketPriority.HIGH,
            TriageTicketFixtures.DEFAULT_SUPPORT_QUEUE_ID,
            TriageTicketFixtures.DEFAULT_REASON,
            7L,
            TriageTicketFixtures.supportActor("support-100"),
            Set.of(TriageTicketFixtures.DEFAULT_TEAM_ID),
            "key-5",
            "corr-1",
            "cmd-1",
            Instant.parse("2026-07-29T18:30:00Z")
        );

        assertThatCode(() -> service.triage(withoutSubcategory)).doesNotThrowAnyException();
        verify(subcategoryCatalogPort, never()).findActiveById(any());
    }

    @Test
    void shouldRejectAMissingQueue() {
        when(supportQueueCatalogPort.findActiveById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.triage(command("key-6"))).isInstanceOf(SupportQueueInvalidException.class);
        verify(telemetry).recordTriageCatalogInvalid("queue");
        verify(triageRepository, never()).applyTriage(any());
    }

    @Test
    void shouldRejectAnInactiveQueueIdenticallyToAMissingOne() {
        when(supportQueueCatalogPort.findActiveById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.triage(command("key-7"))).isInstanceOf(SupportQueueInvalidException.class);
    }

    @Test
    void shouldStillCheckQueueTeamGrantAfterAValidQueueIsResolved() {
        TriageTicketCommand withoutTeamGrant = TriageTicketFixtures.command(
            TICKET_ID, TriageTicketFixtures.supportActor("support-100"), Set.of("UNRELATED-TEAM"), 7L, "key-8"
        );

        assertThatThrownBy(() -> service.triage(withoutTeamGrant)).isInstanceOf(QueueAccessDeniedException.class);
    }
}
