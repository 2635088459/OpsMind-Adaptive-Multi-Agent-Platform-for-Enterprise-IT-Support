package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.support.TicketFixtures;
import dev.opsmind.ticketworkflow.ticket.application.command.CreateTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.CreateTicketResult;
import dev.opsmind.ticketworkflow.ticket.application.event.RequesterPseudonymizer;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketIntegrationEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.IdempotencyKeyReusedException;
import dev.opsmind.ticketworkflow.ticket.application.exception.RequestInProgressException;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.IdempotencyReservationOutcome;
import dev.opsmind.ticketworkflow.ticket.application.idempotency.RequestHashCalculator;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.port.out.AuditRecordPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import dev.opsmind.ticketworkflow.ticket.application.port.out.IdempotencyRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.OutboxEventRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.SlaPolicyResolver;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketDisplayIdGenerator;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketHistoryWriter;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketIdGenerator;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketResolutionCycleRepository;
import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketSlaRepository;
import dev.opsmind.ticketworkflow.ticket.application.service.CreateTicketApplicationService;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("unit")
class CreateTicketIdempotencyReplayTest {

    private TicketRepository ticketRepository;
    private IdempotencyRepository idempotencyRepository;
    private TicketTelemetry telemetry;
    private CreateTicketApplicationService service;

    @BeforeEach
    void setUp() {
        ticketRepository = mock(TicketRepository.class);
        TicketResolutionCycleRepository resolutionCycleRepository = mock(TicketResolutionCycleRepository.class);
        TicketSlaRepository slaRepository = mock(TicketSlaRepository.class);
        TicketHistoryWriter historyWriter = mock(TicketHistoryWriter.class);
        AuditRecordPort auditRecordPort = mock(AuditRecordPort.class);
        OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
        idempotencyRepository = mock(IdempotencyRepository.class);
        TicketIdGenerator ticketIdGenerator = mock(TicketIdGenerator.class);
        TicketDisplayIdGenerator displayIdGenerator = mock(TicketDisplayIdGenerator.class);
        ClockPort clock = mock(ClockPort.class);
        when(clock.now()).thenReturn(Instant.parse("2026-07-23T16:30:00Z"));
        SlaPolicyResolver slaPolicyResolver = mock(SlaPolicyResolver.class);
        telemetry = mock(TicketTelemetry.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        RequesterPseudonymizer pseudonymizer = new RequesterPseudonymizer(
            new dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties(
                "unit-test-secret",
                "unit-test-cursor-secret",
                new dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties.Sla("DEFAULT", Duration.ofHours(4), Duration.ofHours(24), Duration.ofHours(4), Duration.ofDays(7))
            )
        );

        service = new CreateTicketApplicationService(
            ticketRepository, resolutionCycleRepository, slaRepository, historyWriter, auditRecordPort,
            outboxEventRepository, idempotencyRepository, ticketIdGenerator, displayIdGenerator, clock,
            slaPolicyResolver, new RequestHashCalculator(objectMapper),
            new TicketIntegrationEventMapper(pseudonymizer), telemetry, objectMapper
        );
    }

    @Test
    void shouldReturnStoredResultWithoutCreatingAnythingWhenReplayed() {
        // Real, pre-existing test-fixture staleness found live: resolutionCycleId
        // became a required field of the stored/replayed body (deserializeResult)
        // after this fixture was written, but this string was never updated —
        // a real NullPointerException (UUID.fromString(null)) on every replay
        // until this fixture includes it.
        String storedBody = """
            {"ticketId":"%s","displayId":"INC-2048","status":"NEW","createdAt":"2026-07-23T16:30:00Z","version":0,"resolutionCycleId":"%s"}
            """.formatted(UUID.randomUUID(), UUID.randomUUID()).strip();

        when(idempotencyRepository.reserve(any())).thenReturn(
            new IdempotencyReservationOutcome.Replayed(201, storedBody)
        );

        CreateTicketCommand command = TicketFixtures.createTicketCommand();
        CreateTicketResult result = service.create(command);

        assertThat(result.idempotencyReplayed()).isTrue();
        assertThat(result.displayId().value()).isEqualTo("INC-2048");
        assertThat(result.status()).isEqualTo(TicketStatus.NEW);

        verifyNoInteractions(ticketRepository);
        verify(telemetry).recordIdempotencyReplay();
    }

    @Test
    void shouldRejectSameKeyWithDifferentPayload() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.KeyReused());

        CreateTicketCommand command = TicketFixtures.createTicketCommand();

        assertThatThrownBy(() -> service.create(command))
            .isInstanceOf(IdempotencyKeyReusedException.class);

        verifyNoInteractions(ticketRepository);
    }

    @Test
    void shouldRejectFreshInProgressReservation() {
        when(idempotencyRepository.reserve(any())).thenReturn(new IdempotencyReservationOutcome.RequestInProgress());

        CreateTicketCommand command = TicketFixtures.createTicketCommand();

        assertThatThrownBy(() -> service.create(command))
            .isInstanceOf(RequestInProgressException.class);

        verifyNoInteractions(ticketRepository);
    }
}
