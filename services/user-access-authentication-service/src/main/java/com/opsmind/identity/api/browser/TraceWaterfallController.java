package com.opsmind.identity.api.browser;

import com.opsmind.identity.application.dto.TraceWaterfallView;
import com.opsmind.identity.application.exception.TraceAccessDeniedException;
import com.opsmind.identity.application.observability.TraceWaterfallService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * SPEC-SC-014: the authenticated proxy in front of domain 08's real,
 * otherwise-unauthenticated Tempo query API (see {@link
 * com.opsmind.identity.config.TempoQueryProperties}'s own javadoc for the
 * full reasoning this endpoint exists at all). Mapped into {@link
 * com.opsmind.identity.config.SecurityConfig#browserLoginFilterChain}
 * alongside {@link BrowserSessionTokenController} — same session-cookie
 * authentication, no new mechanism.
 *
 * <p>Gated to the {@code support-console} registration specifically:
 * unlike {@link BrowserSessionTokenController} (a mechanism every frontend
 * this service fronts needs identically), a trace waterfall is a
 * support-console-only feature (SPEC-SC-014's own actor: "a support agent/
 * admin") — domain 09's employee-portal session has no legitimate reason to
 * query it.
 */
@RestController
public class TraceWaterfallController {

    private final TraceWaterfallService traceWaterfallService;

    public TraceWaterfallController(TraceWaterfallService traceWaterfallService) {
        this.traceWaterfallService = traceWaterfallService;
    }

    @GetMapping("/api/v1/observability/traces/{traceId}")
    public ResponseEntity<TraceWaterfallView> trace(@PathVariable String traceId, OAuth2AuthenticationToken authentication) {
        String registrationId = authentication.getAuthorizedClientRegistrationId();
        if (!"support-console".equals(registrationId)) {
            throw new TraceAccessDeniedException(registrationId);
        }
        return ResponseEntity.ok(traceWaterfallService.fetch(traceId));
    }
}
