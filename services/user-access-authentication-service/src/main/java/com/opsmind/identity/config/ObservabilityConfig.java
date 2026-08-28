package com.opsmind.identity.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * SPEC-UA-030. Baseline metrics tagging so every metric this service emits
 * is attributable to it in a shared Prometheus/OTel backend — mirrors
 * policy-approval-governance-service's own identically-purposed {@code
 * ObservabilityConfig} (io.micrometer/opentelemetry have been real
 * dependencies since this project's own baseline pom.xml, but this common
 * "service" tag never existed until now — confirmed via grep before
 * building the rest of this spec's own real, identity-specific metrics).
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        return registry -> registry.config().meterFilter(MeterFilter.commonTags(List.of(Tag.of("service", "user-access-authentication-service"))));
    }
}
