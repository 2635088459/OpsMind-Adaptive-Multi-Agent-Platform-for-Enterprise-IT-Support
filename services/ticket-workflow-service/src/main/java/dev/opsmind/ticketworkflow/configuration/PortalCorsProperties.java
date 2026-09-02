package dev.opsmind.ticketworkflow.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * domain 09/10's own frontend (a separate browser origin) calls {@code
 * PublicTicketQueryController}/{@code ConfirmResolutionController}/{@code
 * RequesterReopenTicketController} directly (SPEC-EP-013/016/017) — real
 * CORS is required or the browser blocks the response outright before this
 * app's own JS ever sees it. Unlike user-access-authentication-service's own
 * BFF CORS (which needs {@code allowCredentials(true)} for its session
 * cookie), every endpoint here is Bearer-token-authenticated — no cookie
 * ever crosses this boundary, so credentials stay disabled. Empty by
 * default (deny by default, mirroring every other cross-cutting default in
 * this service) — an operator opts specific frontend origins in.
 */
@ConfigurationProperties(prefix = "opsmind.portal.cors")
public record PortalCorsProperties(List<String> allowedOrigins) {

    public PortalCorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
