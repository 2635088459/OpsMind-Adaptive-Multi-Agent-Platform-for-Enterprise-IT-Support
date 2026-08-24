package com.opsmind.identity.application.service;

import com.opsmind.identity.application.port.out.ClockPort;

import java.time.Instant;

final class FixedClockPort implements ClockPort {

    private final Instant instant;

    FixedClockPort(Instant instant) {
        this.instant = instant;
    }

    @Override
    public Instant now() {
        return instant;
    }
}
