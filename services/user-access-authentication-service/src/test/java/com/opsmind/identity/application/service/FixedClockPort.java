package com.opsmind.identity.application.service;

import com.opsmind.identity.application.port.out.ClockPort;

import java.time.Instant;

final class FixedClockPort implements ClockPort {

    private Instant instant;

    FixedClockPort(Instant instant) {
        this.instant = instant;
    }

    @Override
    public Instant now() {
        return instant;
    }

    /** Moves this clock forward for reconciliation tests that assert a time-driven transition only fires once its instant is reached. */
    void advanceTo(Instant instant) {
        this.instant = instant;
    }
}
