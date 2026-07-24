package dev.opsmind.ticketworkflow.ticket.application.port.out;

import java.time.Instant;

public interface ClockPort {

    Instant now();
}
