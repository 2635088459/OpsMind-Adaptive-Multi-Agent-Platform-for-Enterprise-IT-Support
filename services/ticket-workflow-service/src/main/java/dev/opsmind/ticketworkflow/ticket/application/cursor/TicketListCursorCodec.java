package dev.opsmind.ticketworkflow.ticket.application.cursor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.InvalidCursorException;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketListCursor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Base64;

/**
 * Encodes/decodes the opaque, signed List Requester Tickets cursor
 * (SPEC-TW-003 §8): {@code base64url(payload) + "." + base64url(HMAC-SHA-
 * 256(payload))}. {@link #decode} only checks structural validity
 * (format, signature, version, expiry); {@link #requireMatch} separately
 * checks that the cursor is bound to the current request's filters, sort,
 * and principal — kept apart so each concern is independently testable
 * and so a single {@link InvalidCursorException} covers every failure mode
 * without revealing which one occurred (§15).
 */
@Component
public class TicketListCursorCodec {

    private final ObjectMapper objectMapper;
    private final TicketListCursorSigner signer;

    public TicketListCursorCodec(ObjectMapper objectMapper, TicketListCursorSigner signer) {
        this.objectMapper = objectMapper;
        this.signer = signer;
    }

    public String encode(TicketListCursor cursor) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(cursor);
            byte[] signature = signer.sign(payload);
            return base64Url(payload) + "." + base64Url(signature);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to encode list cursor", e);
        }
    }

    public TicketListCursor decode(String token, Instant now) {
        if (token == null || token.isBlank()) {
            throw new InvalidCursorException();
        }
        String[] parts = token.split("\\.", 2);
        if (parts.length != 2) {
            throw new InvalidCursorException();
        }

        byte[] payload;
        byte[] signature;
        try {
            payload = Base64.getUrlDecoder().decode(parts[0]);
            signature = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException e) {
            throw new InvalidCursorException();
        }

        if (!signer.verify(payload, signature)) {
            throw new InvalidCursorException();
        }

        TicketListCursor cursor;
        try {
            cursor = objectMapper.readValue(payload, TicketListCursor.class);
        } catch (java.io.IOException e) {
            throw new InvalidCursorException();
        }

        if (cursor.version() != TicketListCursor.CURRENT_VERSION) {
            throw new InvalidCursorException();
        }
        if (cursor.expiresAt() == null || !now.isBefore(cursor.expiresAt())) {
            throw new InvalidCursorException();
        }

        return cursor;
    }

    public void requireMatch(TicketListCursor cursor, String expectedFilterFingerprint, String expectedPrincipalSubject) {
        boolean matches = TicketListCursor.SORT.equals(cursor.sort())
            && TicketListCursor.OPERATION.equals(cursor.operation())
            && expectedFilterFingerprint.equals(cursor.filterFingerprint())
            && expectedPrincipalSubject.equals(cursor.principalSubject());
        if (!matches) {
            throw new InvalidCursorException();
        }
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
