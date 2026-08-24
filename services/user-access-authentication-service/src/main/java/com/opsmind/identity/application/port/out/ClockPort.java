package com.opsmind.identity.application.port.out;

import java.time.Instant;

/** 13-package-and-class-design §Output Ports. Lets application/domain logic and tests use a controllable, server-side UTC clock (09-concurrency-and-idempotency). */
public interface ClockPort {

    Instant now();
}
