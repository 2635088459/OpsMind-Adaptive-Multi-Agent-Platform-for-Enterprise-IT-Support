package com.opsmind.policygovernance.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Baseline metrics tagging so every metric this service emits is
 * attributable to it in a shared Prometheus/OTel backend. The rich
 * governance-specific metrics and tracing spans (decision latency, approval
 * SLA, evaluator failure rate) are owned by SPEC-PG-003's
 * 12-observability slice, once there is real work to measure.
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        return registry -> registry.config().meterFilter(
            MeterFilter.commonTags(java.util.List.of(io.micrometer.core.instrument.Tag.of("service", "policy-approval-governance-service")))
        );
    }
}
