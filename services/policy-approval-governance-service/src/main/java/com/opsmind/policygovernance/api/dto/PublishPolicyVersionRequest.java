package com.opsmind.policygovernance.api.dto;

import java.time.Instant;

public record PublishPolicyVersionRequest(
    Instant effectiveFrom
) {
}
