package com.opsmind.policygovernance.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * SPEC-XOBS-001 Part A: registers {@link GovernanceNotificationProperties}, mirroring
 * how {@link org.springframework.boot.context.properties.EnableConfigurationProperties}
 * is used for {@code PortalCorsProperties} in {@code SecurityConfig}.
 */
@Configuration
@EnableConfigurationProperties(GovernanceNotificationProperties.class)
public class NotificationConfig {
}
