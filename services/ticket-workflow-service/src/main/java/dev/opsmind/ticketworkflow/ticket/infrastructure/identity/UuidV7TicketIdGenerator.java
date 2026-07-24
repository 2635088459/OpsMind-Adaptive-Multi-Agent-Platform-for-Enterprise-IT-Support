package dev.opsmind.ticketworkflow.ticket.infrastructure.identity;

import dev.opsmind.ticketworkflow.ticket.application.port.out.TicketIdGenerator;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketId;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * Generates time-ordered UUIDv7 identifiers (RFC 9562) so ticket ids sort
 * roughly by creation time without leaking a database sequence across
 * services, per 13-package-and-class-design §58.
 */
@Component
public class UuidV7TicketIdGenerator implements TicketIdGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public TicketId generate() {
        return TicketId.of(generateUuidV7());
    }

    private static UUID generateUuidV7() {
        long timestampMillis = Instant.now().toEpochMilli();
        byte[] value = new byte[16];

        value[0] = (byte) (timestampMillis >>> 40);
        value[1] = (byte) (timestampMillis >>> 32);
        value[2] = (byte) (timestampMillis >>> 24);
        value[3] = (byte) (timestampMillis >>> 16);
        value[4] = (byte) (timestampMillis >>> 8);
        value[5] = (byte) timestampMillis;

        byte[] randomBytes = new byte[10];
        RANDOM.nextBytes(randomBytes);
        System.arraycopy(randomBytes, 0, value, 6, 10);

        value[6] = (byte) ((value[6] & 0x0F) | 0x70);
        value[8] = (byte) ((value[8] & 0x3F) | 0x80);

        long mostSigBits = 0;
        for (int i = 0; i < 8; i++) {
            mostSigBits = (mostSigBits << 8) | (value[i] & 0xFF);
        }
        long leastSigBits = 0;
        for (int i = 8; i < 16; i++) {
            leastSigBits = (leastSigBits << 8) | (value[i] & 0xFF);
        }

        return new UUID(mostSigBits, leastSigBits);
    }
}
