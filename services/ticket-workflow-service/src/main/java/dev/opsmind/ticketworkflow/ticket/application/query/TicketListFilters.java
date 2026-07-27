package dev.opsmind.ticketworkflow.ticket.application.query;

import dev.opsmind.ticketworkflow.ticket.api.exception.RequestValidationException;
import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;
import dev.opsmind.ticketworkflow.ticket.domain.value.TicketStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Approved List Requester Tickets filters (SPEC-TW-003 §5). Bean identity
 * is not enough to bind a cursor to "the same filters" across requests, so
 * {@link #fingerprint()} produces a stable, order-independent digest that
 * excludes {@code limit} and {@code cursor} per §5.
 */
public record TicketListFilters(
    Set<TicketStatus> statuses,
    Set<ApplicationCode> applicationCodes,
    Instant createdFrom,
    Instant createdTo
) {

    private static final int MAX_STATUSES = 10;

    public TicketListFilters {
        statuses = statuses == null || statuses.isEmpty() ? Set.of() : EnumSet.copyOf(statuses);
        applicationCodes = applicationCodes == null || applicationCodes.isEmpty() ? Set.of() : EnumSet.copyOf(applicationCodes);

        if (statuses.size() > MAX_STATUSES) {
            throw new RequestValidationException("status accepts at most " + MAX_STATUSES + " values");
        }
        if (createdFrom != null && createdTo != null && !createdFrom.isBefore(createdTo)) {
            throw new RequestValidationException("createdFrom must be before createdTo");
        }
    }

    public static TicketListFilters none() {
        return new TicketListFilters(Set.of(), Set.of(), null, null);
    }

    /**
     * {@code sha256:<hex>} over a canonical, sorted, UTC-normalized
     * representation, so two logically identical filter sets always
     * produce the same fingerprint regardless of set iteration order.
     */
    public String fingerprint() {
        String canonical = "statuses=" + sortedNames(statuses)
            + "|applicationCodes=" + sortedNames(applicationCodes)
            + "|createdFrom=" + (createdFrom == null ? "" : createdFrom.toString())
            + "|createdTo=" + (createdTo == null ? "" : createdTo.toString());
        return "sha256:" + sha256Hex(canonical);
    }

    private static String sortedNames(Set<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
