package dev.opsmind.ticketworkflow.ticket.infrastructure.clock;

import dev.opsmind.ticketworkflow.ticket.application.port.out.ClockPort;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class SystemClockAdapter implements ClockPort {

    @Override
    public Instant now() {
        return Instant.now();
    }
}
