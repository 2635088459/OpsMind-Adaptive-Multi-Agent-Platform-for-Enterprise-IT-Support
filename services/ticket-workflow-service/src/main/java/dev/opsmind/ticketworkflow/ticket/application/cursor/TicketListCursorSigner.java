package dev.opsmind.ticketworkflow.ticket.application.cursor;

import dev.opsmind.ticketworkflow.configuration.TicketWorkflowProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

/**
 * HMAC-SHA-256 signer for the List Requester Tickets pagination cursor
 * (SPEC-TW-003 §8). A service-controlled key (not derived from user input)
 * makes the cursor tamper-resistant: any modification to the payload
 * invalidates the signature.
 */
@Component
public class TicketListCursorSigner {

    private final SecretKeySpec key;

    public TicketListCursorSigner(TicketWorkflowProperties properties) {
        String secret = properties.listCursorSigningSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("opsmind.ticket.list-cursor-signing-secret must be configured");
        }
        this.key = new SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
    }

    public byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("failed to sign list cursor", e);
        }
    }

    public boolean verify(byte[] payload, byte[] signature) {
        return MessageDigest.isEqual(sign(payload), signature);
    }
}
