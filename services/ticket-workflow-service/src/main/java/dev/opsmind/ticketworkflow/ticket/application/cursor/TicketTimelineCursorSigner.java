package dev.opsmind.ticketworkflow.ticket.application.cursor;

import dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

/**
 * HMAC-SHA-256 signer for the Ticket Timeline pagination cursor
 * (SPEC-TW-006 §15). Reuses the same {@code opsmind.ticket.list-cursor-
 * signing-secret} as {@link TicketListCursorSigner} and {@link
 * dev.opsmind.ticketworkflow.ticket.application.cursor.SupportQueueCursorSigner}
 * rather than introducing a third secret: each cursor payload shape is
 * structurally distinct (different required fields), so Jackson's strict
 * deserialization already prevents one cursor type from ever being
 * accepted as another, even under a shared key.
 */
@Component
public class TicketTimelineCursorSigner {

    private final SecretKeySpec key;

    public TicketTimelineCursorSigner(TicketWorkflowProperties properties) {
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
            throw new IllegalStateException("failed to sign ticket timeline cursor", e);
        }
    }

    public boolean verify(byte[] payload, byte[] signature) {
        return MessageDigest.isEqual(sign(payload), signature);
    }
}
