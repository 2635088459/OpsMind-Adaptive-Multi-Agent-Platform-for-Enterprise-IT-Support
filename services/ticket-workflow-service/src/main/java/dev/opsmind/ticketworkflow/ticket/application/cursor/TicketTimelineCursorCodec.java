package dev.opsmind.ticketworkflow.ticket.application.cursor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.InvalidCursorException;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineCursor;
import dev.opsmind.ticketworkflow.ticket.application.query.TicketTimelineSortVersion;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Base64;

/**
 * Encodes/decodes the opaque, signed Ticket Timeline cursor (SPEC-TW-006
 * §15): {@code base64url(payload) + "." + base64url(HMAC-SHA-256(payload))}.
 * {@link #decode} only checks structural validity (format, signature,
 * version, expiry); {@link #requireMatch} separately checks that the
 * cursor is bound to the current request's operation, sort version,
 * visibility-policy version, Ticket, authorization scope, resolved view,
 * and principal — kept apart so each concern is independently testable
 * and a single {@link InvalidCursorException} covers every failure mode
 * without revealing which one occurred.
 */
@Component
public class TicketTimelineCursorCodec {

    private final ObjectMapper objectMapper;
    private final TicketTimelineCursorSigner signer;

    public TicketTimelineCursorCodec(ObjectMapper objectMapper, TicketTimelineCursorSigner signer) {
        this.objectMapper = objectMapper;
        this.signer = signer;
    }

    public String encode(TicketTimelineCursor cursor) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(cursor);
            byte[] signature = signer.sign(payload);
            return base64Url(payload) + "." + base64Url(signature);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to encode ticket timeline cursor", e);
        }
    }

    public TicketTimelineCursor decode(String token, Instant now) {
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

        TicketTimelineCursor cursor;
        try {
            cursor = objectMapper.readValue(payload, TicketTimelineCursor.class);
        } catch (java.io.IOException e) {
            throw new InvalidCursorException();
        }

        if (cursor.version() != TicketTimelineCursor.CURRENT_VERSION) {
            throw new InvalidCursorException();
        }
        if (cursor.expiresAt() == null || !now.isBefore(cursor.expiresAt())) {
            throw new InvalidCursorException();
        }

        return cursor;
    }

    public void requireMatch(
        TicketTimelineCursor cursor,
        String expectedTicketId,
        String expectedScopeFingerprint,
        String expectedViewType,
        String expectedPrincipalSubject
    ) {
        boolean matches = TicketTimelineCursor.OPERATION.equals(cursor.operation())
            && cursor.sortVersion() == TicketTimelineSortVersion.CURRENT_VERSION
            && cursor.visibilityPolicyVersion() == TicketTimelineCursor.CURRENT_VISIBILITY_POLICY_VERSION
            && expectedTicketId.equals(cursor.ticketId())
            && expectedScopeFingerprint.equals(cursor.scopeFingerprint())
            && expectedViewType.equals(cursor.viewType())
            && expectedPrincipalSubject.equals(cursor.principalSubject());
        if (!matches) {
            throw new InvalidCursorException();
        }
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
