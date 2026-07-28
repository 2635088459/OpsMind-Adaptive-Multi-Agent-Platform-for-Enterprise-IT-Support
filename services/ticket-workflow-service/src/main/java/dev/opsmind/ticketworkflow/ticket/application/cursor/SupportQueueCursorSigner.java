package dev.opsmind.ticketworkflow.ticket.application.cursor;

import dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

/**
 * HMAC-SHA-256 signer for the Support Queue pagination cursor (SPEC-TW-005
 * §14). Reuses the same {@code opsmind.ticket.list-cursor-signing-secret}
 * as {@link TicketListCursorSigner} rather than introducing a second
 * secret: the two cursor payload shapes are structurally distinct
 * (different required fields), so Jackson's strict deserialization already
 * prevents one cursor type from ever being accepted as the other, even
 * under a shared key.
 */
@Component
public class SupportQueueCursorSigner {

    private final SecretKeySpec key;

    public SupportQueueCursorSigner(TicketWorkflowProperties properties) {
        String secret = properties.listCursorSigningSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("opsmind.ticket.list-cursor-signing-secret must be configured");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("failed to sign support queue cursor", e);
        }
    }

    public boolean verify(byte[] payload, byte[] signature) {
        return MessageDigest.isEqual(sign(payload), signature);
    }
}
