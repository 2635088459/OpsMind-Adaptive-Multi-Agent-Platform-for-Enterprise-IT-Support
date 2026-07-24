package dev.opsmind.ticketworkflow.ticket.application.event;

import dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties;
import dev.opsmind.ticketworkflow.ticket.domain.value.RequesterId;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * Pseudonymizes the requester identity before it leaves the service boundary
 * in an integration event, per BI-102. Unsalted SHA-256 is not acceptable for
 * an enumerable identifier space, so this uses a service-controlled HMAC key.
 */
@Component
public class RequesterPseudonymizer {

    private final SecretKeySpec key;

    public RequesterPseudonymizer(TicketWorkflowProperties properties) {
        String secret = properties.requesterPseudonymizationSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("opsmind.ticket.requester-pseudonymization-secret must be configured");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public String pseudonymize(RequesterId requesterId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] hash = mac.doFinal(requesterId.value().getBytes(StandardCharsets.UTF_8));
            return "hmac-sha256:" + HexFormat.of().formatHex(hash);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("failed to pseudonymize requester id", e);
        }
    }
}
