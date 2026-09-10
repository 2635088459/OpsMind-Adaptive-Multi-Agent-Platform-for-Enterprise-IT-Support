package com.opsmind.policygovernance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SPEC-XOBS-001 Part A. Config for the approval-notification channel behind
 * {@link com.opsmind.policygovernance.application.port.ApprovalNotificationPort}.
 *
 * <p>{@code mode} selects the adapter: {@code noop} (default) keeps
 * {@link com.opsmind.policygovernance.infrastructure.notification.NoOpApprovalNotificationAdapter}
 * — so every deployment and test that has not opted in is unchanged — while
 * {@code smtp} activates
 * {@link com.opsmind.policygovernance.infrastructure.notification.SmtpApprovalNotificationAdapter},
 * which sends real mail via {@code spring.mail.*} (Mailpit in the local stack).
 *
 * <p>Governance holds no user directory: an {@code ApprovalRequest} carries a
 * {@code requestedBy} subject/username and a {@code sourceDomain}, never an email
 * address. So a new request notifies one configured {@code approverAddress}, and a
 * decision notifies the requester at {@code requestedBy} verbatim if it already looks
 * like an address, otherwise {@code requestedBy + "@" + emailDomain}.
 */
@ConfigurationProperties(prefix = "opsmind.governance.notifications")
public record GovernanceNotificationProperties(
    String mode,
    String fromAddress,
    String approverAddress,
    String emailDomain
) {

    public GovernanceNotificationProperties {
        mode = (mode == null || mode.isBlank()) ? "noop" : mode.trim().toLowerCase();
        fromAddress = blankToDefault(fromAddress, "it-governance@opsmind.dev");
        approverAddress = blankToDefault(approverAddress, "it-approvers@opsmind.dev");
        emailDomain = blankToDefault(emailDomain, "opsmind.dev");
    }

    private static String blankToDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    /** Resolve a decision-notification recipient from the request's {@code requestedBy}. */
    public String requesterAddress(String requestedBy) {
        if (requestedBy == null || requestedBy.isBlank()) {
            return approverAddress;
        }
        String trimmed = requestedBy.trim();
        return trimmed.contains("@") ? trimmed : trimmed + "@" + emailDomain;
    }
}
