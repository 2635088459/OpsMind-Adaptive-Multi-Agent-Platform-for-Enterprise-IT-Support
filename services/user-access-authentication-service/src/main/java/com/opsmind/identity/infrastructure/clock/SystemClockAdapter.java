package com.opsmind.identity.infrastructure.clock;

import com.opsmind.identity.application.port.out.ClockPort;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/** 09-concurrency-and-idempotency: "Time comparisons use server-side UTC and tests inject Clock." */
@Component
public class SystemClockAdapter implements ClockPort {

    private final Clock clock;

    public SystemClockAdapter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Instant now() {
        return clock.instant();
    }
}
