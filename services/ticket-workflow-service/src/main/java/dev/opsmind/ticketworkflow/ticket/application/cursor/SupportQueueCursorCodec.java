package dev.opsmind.ticketworkflow.ticket.application.cursor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opsmind.ticketworkflow.ticket.application.exception.InvalidCursorException;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueCursor;
import dev.opsmind.ticketworkflow.ticket.application.query.SupportQueueSortVersion;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Base64;

/**
 * Encodes/decodes the opaque, signed Support Queue cursor (SPEC-TW-005
 * §14): {@code base64url(payload) + "." + base64url(HMAC-SHA-256(payload))}.
 * {@link #decode} only checks structural validity (format, signature,
 * version, expiry); {@link #requireMatch} separately checks that the
 * cursor is bound to the current request's operation, sort version,
 * filters, authorization scope, and principal — kept apart so each
 * concern is independently testable and a single {@link
 * InvalidCursorException} covers every failure mode without revealing
 * which one occurred.
 */
@Component
public class SupportQueueCursorCodec {

    private final ObjectMapper objectMapper;
    private final SupportQueueCursorSigner signer;

    public SupportQueueCursorCodec(ObjectMapper objectMapper, SupportQueueCursorSigner signer) {
        this.objectMapper = objectMapper;
        this.signer = signer;
    }

    public String encode(SupportQueueCursor cursor) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(cursor);
            byte[] signature = signer.sign(payload);
            return base64Url(payload) + "." + base64Url(signature);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to encode support queue cursor", e);
        }
    }

    public SupportQueueCursor decode(String token, Instant now) {
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

        SupportQueueCursor cursor;
        try {
            cursor = objectMapper.readValue(payload, SupportQueueCursor.class);
        } catch (java.io.IOException e) {
            throw new InvalidCursorException();
        }

        if (cursor.version() != SupportQueueCursor.CURRENT_VERSION) {
            throw new InvalidCursorException();
        }
        if (cursor.expiresAt() == null || !now.isBefore(cursor.expiresAt())) {
            throw new InvalidCursorException();
        }

        return cursor;
    }

    public void requireMatch(
        SupportQueueCursor cursor,
        String expectedFilterFingerprint,
        String expectedScopeFingerprint,
        String expectedPrincipalSubject
    ) {
        boolean matches = SupportQueueCursor.OPERATION.equals(cursor.operation())
            && cursor.sortVersion() == SupportQueueSortVersion.CURRENT_VERSION
            && expectedFilterFingerprint.equals(cursor.filterFingerprint())
            && expectedScopeFingerprint.equals(cursor.scopeFingerprint())
            && expectedPrincipalSubject.equals(cursor.principalSubject());
        if (!matches) {
            throw new InvalidCursorException();
        }
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
