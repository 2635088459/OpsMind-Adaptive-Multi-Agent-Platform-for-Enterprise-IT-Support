package com.opsmind.policygovernance.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * A single injectable {@link Clock} bean so application services never call
 * {@code Instant.now()} directly — tests substitute a fixed clock instead of
 * sleeping or asserting on wall-clock time.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
