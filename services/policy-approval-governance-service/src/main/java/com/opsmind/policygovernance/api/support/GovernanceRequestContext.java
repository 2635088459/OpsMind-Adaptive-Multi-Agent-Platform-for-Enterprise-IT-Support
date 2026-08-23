package com.opsmind.policygovernance.api.support;

import com.opsmind.policygovernance.api.exception.RequestValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;

/**
 * Extracts the two things every governance command API requires beyond its
 * own body (api-contract §API Impact): the authenticated actor and a
 * correlation id linking the call back to its source-domain request.
 */
public final class GovernanceRequestContext {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private GovernanceRequestContext() {
    }

    public static String actorId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new RequestValidationException("an authenticated actor is required");
        }
        return authentication.getName();
    }

    public static String correlationId(HttpServletRequest request) {
        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            throw new RequestValidationException(CORRELATION_ID_HEADER + " header is required");
        }
        return correlationId;
    }

    public static String causationId(HttpServletRequest request) {
        return request.getHeader("X-Causation-Id");
    }
}
