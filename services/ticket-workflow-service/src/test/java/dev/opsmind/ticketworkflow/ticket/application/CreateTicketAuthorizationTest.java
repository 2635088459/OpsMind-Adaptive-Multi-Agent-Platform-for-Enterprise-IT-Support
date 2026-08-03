package dev.opsmind.ticketworkflow.ticket.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.support.TicketFixtures;
import dev.opsmind.ticketworkflow.ticket.application.command.CreateTicketCommand;
import dev.opsmind.ticketworkflow.ticket.application.event.RequesterPseudonymizer;
import dev.opsmind.ticketworkflow.ticket.application.event.TicketIntegrationEventMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@Tag("unit")
class CreateTicketAuthorizationTest {

    @Test
    void shouldRejectActorMissingCreateScope() {
        TicketRepository ticketRepository = mock(TicketRepository.class);
        TicketResolutionCycleRepository resolutionCycleRepository = mock(TicketResolutionCycleRepository.class);
        TicketSlaRepository slaRepository = mock(TicketSlaRepository.class);
        TicketHistoryWriter historyWriter = mock(TicketHistoryWriter.class);
        AuditRecordPort auditRecordPort = mock(AuditRecordPort.class);
        OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
        IdempotencyRepository idempotencyRepository = mock(IdempotencyRepository.class);
        TicketIdGenerator ticketIdGenerator = mock(TicketIdGenerator.class);
        TicketDisplayIdGenerator displayIdGenerator = mock(TicketDisplayIdGenerator.class);
        ClockPort clock = mock(ClockPort.class);
        SlaPolicyResolver slaPolicyResolver = mock(SlaPolicyResolver.class);
        TicketTelemetry telemetry = mock(TicketTelemetry.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        RequesterPseudonymizer pseudonymizer = new RequesterPseudonymizer(
            new dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties(
                "unit-test-secret",
                "unit-test-cursor-secret",
                new dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties.Sla("DEFAULT", Duration.ofHours(4), Duration.ofHours(24), Duration.ofHours(4), Duration.ofDays(7))
            )
        );

        CreateTicketApplicationService service = new CreateTicketApplicationService(
            ticketRepository, resolutionCycleRepository, slaRepository, historyWriter, auditRecordPort,
            outboxEventRepository, idempotencyRepository, ticketIdGenerator, displayIdGenerator, clock,
            slaPolicyResolver, new RequestHashCalculator(objectMapper),
            new TicketIntegrationEventMapper(pseudonymizer), telemetry, objectMapper
        );

        CreateTicketCommand command = TicketFixtures.createTicketCommand(
            TicketFixtures.employeeActorWithoutCreateScope(), "idem-key-1"
        );

        assertThatThrownBy(() -> service.create(command))
            .isInstanceOf(TicketAuthorizationException.class);

        verifyNoInteractions(
            ticketRepository, resolutionCycleRepository, slaRepository, historyWriter,
            auditRecordPort, outboxEventRepository, idempotencyRepository
        );
        verify(telemetry).recordAuthorizationDenied("createTicket");
    }
}
