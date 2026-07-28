package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.support.TicketFixtures;
import dev.opsmind.ticketworkflow.ticket.application.command.CreateTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.CreateTicketResult;
import dev.opsmind.ticketworkflow.ticket.application.event.RequesterPseudonymizer;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketIntegrationEventMapper;
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
import dev.opsmind.ticketworkflow.ticket.application.port.out.ResolvedSlaPolicy;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SlaPolicyResolver;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketDisplayIdGenerator;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketIdGenerator;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketResolutionCycleRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketSlaRepository;
import dev.opsmind.ticketworkflow.ticket.application.service.CreateTicketApplicationService;
import dev.opsmind.ticketworkflow.ticket.domain.model.Ticket;
import dev.opsmind.ticketworkflow.ticket.domain.model.TicketResolutionCycle;
import dev.opsmind.ticketworkflow.ticket.domain.model.TicketSlaCycle;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
class CreateTicketApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-23T16:30:00Z");

    private TicketRepository ticketRepository;
    private TicketResolutionCycleRepository resolutionCycleRepository;
    private TicketSlaRepository slaRepository;
    private TicketHistoryWriter historyWriter;
    private AuditRecordPort auditRecordPort;
    private OutboxEventRepository outboxEventRepository;
    private IdempotencyRepository idempotencyRepository;
    private TicketIdGenerator ticketIdGenerator;
    private TicketDisplayIdGenerator displayIdGenerator;
    private ClockPort clock;
    private SlaPolicyResolver slaPolicyResolver;
    private TicketTelemetry telemetry;

    private CreateTicketApplicationService service;

    private final TicketId fixedTicketId = TicketId.of(UUID.randomUUID());
    private final TicketDisplayId fixedDisplayId = TicketDisplayId.of("INC-2048");

    @BeforeEach
    void setUp() {
        ticketRepository = mock(TicketRepository.class);
        resolutionCycleRepository = mock(TicketResolutionCycleRepository.class);
        slaRepository = mock(TicketSlaRepository.class);
        historyWriter = mock(TicketHistoryWriter.class);
        auditRecordPort = mock(AuditRecordPort.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        idempotencyRepository = mock(IdempotencyRepository.class);
        ticketIdGenerator = mock(TicketIdGenerator.class);
        displayIdGenerator = mock(TicketDisplayIdGenerator.class);
        clock = mock(ClockPort.class);
        slaPolicyResolver = mock(SlaPolicyResolver.class);
        telemetry = mock(TicketTelemetry.class);

        when(clock.now()).thenReturn(NOW);
        when(ticketIdGenerator.generate()).thenReturn(fixedTicketId);
        when(displayIdGenerator.generate()).thenReturn(fixedDisplayId);
        when(slaPolicyResolver.resolve(any(), any())).thenReturn(new ResolvedSlaPolicy(
            "DEFAULT", NOW.plusSeconds(4 * 3600), NOW.plusSeconds(24 * 3600)
        ));
        when(idempotencyRepository.reserve(any())).thenReturn(
            new IdempotencyReservationOutcome.Reserved(UUID.randomUUID())
        );

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        RequesterPseudonymizer pseudonymizer = new RequesterPseudonymizer(
            new dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties(
                "unit-test-secret",
                "unit-test-cursor-secret",
                new dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties.Sla(
                    "DEFAULT", java.time.Duration.ofHours(4), java.time.Duration.ofHours(24), java.time.Duration.ofHours(4)
                )
            )
        );

        service = new CreateTicketApplicationService(
            ticketRepository,
            resolutionCycleRepository,
            slaRepository,
            historyWriter,
            auditRecordPort,
            outboxEventRepository,
            idempotencyRepository,
            ticketIdGenerator,
            displayIdGenerator,
            clock,
            slaPolicyResolver,
            new RequestHashCalculator(objectMapper),
            new TicketIntegrationEventMapper(pseudonymizer),
            telemetry,
            objectMapper
        );
    }

    @Test
    void shouldPersistTicketResolutionCycleSlaCycleHistoryAuditAndOutboxInOneCall() {
        CreateTicketCommand command = TicketFixtures.createTicketCommand();

        CreateTicketResult result = service.create(command);

        assertThat(result.ticketId()).isEqualTo(fixedTicketId);
        assertThat(result.displayId()).isEqualTo(fixedDisplayId);
        assertThat(result.status()).isEqualTo(TicketStatus.NEW);
        assertThat(result.version()).isZero();
        assertThat(result.idempotencyReplayed()).isFalse();

        ArgumentCaptor<Ticket> ticketCaptor = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketRepository).save(ticketCaptor.capture());
        Ticket savedTicket = ticketCaptor.getValue();
        assertThat(savedTicket.id()).isEqualTo(fixedTicketId);
        assertThat(savedTicket.displayId()).isEqualTo(fixedDisplayId);
        assertThat(savedTicket.status()).isEqualTo(TicketStatus.NEW);

        ArgumentCaptor<TicketResolutionCycle> cycleCaptor = ArgumentCaptor.forClass(TicketResolutionCycle.class);
        verify(resolutionCycleRepository).save(cycleCaptor.capture());
        assertThat(cycleCaptor.getValue().ticketId()).isEqualTo(fixedTicketId);
        assertThat(cycleCaptor.getValue().cycleNumber()).isEqualTo(1);
        assertThat(cycleCaptor.getValue().resolutionCycleId()).isEqualTo(savedTicket.currentResolutionCycleId());

        ArgumentCaptor<TicketSlaCycle> slaCaptor = ArgumentCaptor.forClass(TicketSlaCycle.class);
        verify(slaRepository).save(slaCaptor.capture());
        assertThat(slaCaptor.getValue().ticketId()).isEqualTo(fixedTicketId);
        assertThat(slaCaptor.getValue().resolutionCycleId()).isEqualTo(savedTicket.currentResolutionCycleId());
        assertThat(slaCaptor.getValue().policyId()).isEqualTo("DEFAULT");

        ArgumentCaptor<TicketStatusHistoryEntry> historyCaptor = ArgumentCaptor.forClass(TicketStatusHistoryEntry.class);
        verify(historyWriter).appendInitial(historyCaptor.capture());
        assertThat(historyCaptor.getValue().ticketId()).isEqualTo(fixedTicketId);
        assertThat(historyCaptor.getValue().fromStatus()).isNull();
        assertThat(historyCaptor.getValue().toStatus()).isEqualTo(TicketStatus.NEW);
        assertThat(historyCaptor.getValue().transitionId()).isEqualTo("SM-001");
        assertThat(historyCaptor.getValue().aggregateVersion()).isZero();

        ArgumentCaptor<AuditRecordEntry> auditCaptor = ArgumentCaptor.forClass(AuditRecordEntry.class);
        verify(auditRecordPort).append(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("TICKET_CREATED");
        assertThat(auditCaptor.getValue().resourceId()).isEqualTo(fixedTicketId.toString());
        assertThat(auditCaptor.getValue().ticketStatusAfter()).isEqualTo("NEW");
        assertThat(auditCaptor.getValue().outcome()).isEqualTo("SUCCESS");

        ArgumentCaptor<OutboxEventEntry> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntry.class);
        verify(outboxEventRepository).append(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().eventType()).isEqualTo("ticket.created");
        assertThat(outboxCaptor.getValue().routingKey()).isEqualTo("ticket.created.v1");
        assertThat(outboxCaptor.getValue().ticketId()).isEqualTo(fixedTicketId);
        assertThat(outboxCaptor.getValue().aggregateVersion()).isZero();
        assertThat(outboxCaptor.getValue().payload()).doesNotContainKey("title");
        assertThat(outboxCaptor.getValue().payload()).doesNotContainKey("description");
        assertThat(outboxCaptor.getValue().payload()).containsEntry("initialStatus", "NEW");

        verify(idempotencyRepository).complete(any(), any());
        verify(telemetry).recordCreated(command.applicationCode(), command.source());
    }

    @Test
    void shouldRegenerateDisplayIdAndRetryOnCollision() {
        dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId collidingDisplayId =
            dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId.of("INC-1");
        dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId retryDisplayId =
            dev.opsmind.ticketworkflow.ticket.domain.value.TicketDisplayId.of("INC-2");
        when(displayIdGenerator.generate()).thenReturn(collidingDisplayId, retryDisplayId);

        org.mockito.Mockito.doThrow(new dev.opsmind.ticketworkflow.ticket.application.exception.DisplayIdCollisionException(
                collidingDisplayId.value(), null
            ))
            .doNothing()
            .when(ticketRepository).save(any());

        CreateTicketResult result = service.create(TicketFixtures.createTicketCommand());

        assertThat(result.displayId()).isEqualTo(retryDisplayId);
        verify(ticketRepository, org.mockito.Mockito.times(2)).save(any());
    }
}
