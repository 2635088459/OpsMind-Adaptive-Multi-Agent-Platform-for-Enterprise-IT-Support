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
 * Approved Support Queue filters (SPEC-TW-005 §9). {@link #fingerprint()}
 * binds a pagination cursor to "the same filters" (§14) and deliberately
 * excludes {@code evaluationTime}, which the cursor binds as its own,
 * separate field (§14 lists Filters and Evaluation time as distinct bound
 * components).
 */
public record SupportQueueFilters(
    Set<TicketStatus> statuses,
    Set<SupportQueuePriority> priorities,
    Set<ApplicationCode> applicationCodes,
    Set<String> assignedTeams,
    String assignedAgent,
    boolean unassignedOnly,
    Set<SlaQueueState> slaStates,
    Instant createdFrom,
    Instant createdTo
) {

    private static final Set<TicketStatus> TERMINAL_STATUSES = EnumSet.of(TicketStatus.CLOSED, TicketStatus.CANCELLED);
    /**
     * Derived from {@link TicketStatus} rather than a fixed literal: every
     * non-terminal status is a legal filter value, so the cap must track
     * however many of those exist today, not the count from whenever this
     * bound was first written (SPEC-TW-007 added {@code TRIAGED}, which
     * silently broke a hardcoded {@code 10} here).
     */
    private static final int MAX_STATUSES = TicketStatus.values().length - TERMINAL_STATUSES.size();
    private static final int MAX_PRIORITIES = 5;
    private static final int MAX_APPLICATION_CODES = 4;

    public SupportQueueFilters {
        statuses = statuses == null || statuses.isEmpty() ? Set.of() : EnumSet.copyOf(statuses);
        priorities = priorities == null || priorities.isEmpty() ? Set.of() : EnumSet.copyOf(priorities);
        applicationCodes = applicationCodes == null || applicationCodes.isEmpty() ? Set.of() : EnumSet.copyOf(applicationCodes);
        assignedTeams = assignedTeams == null ? Set.of() : Set.copyOf(assignedTeams);
        slaStates = slaStates == null || slaStates.isEmpty() ? Set.of() : EnumSet.copyOf(slaStates);

        if (statuses.size() > MAX_STATUSES) {
            throw new RequestValidationException("status accepts at most " + MAX_STATUSES + " values");
        }
        if (!TERMINAL_STATUSES.stream().filter(statuses::contains).collect(Collectors.toSet()).isEmpty()) {
            throw new RequestValidationException("status must not include CLOSED or CANCELLED for the operational queue");
        }
        if (priorities.size() > MAX_PRIORITIES) {
            throw new RequestValidationException("priority accepts at most " + MAX_PRIORITIES + " values");
        }
        if (applicationCodes.size() > MAX_APPLICATION_CODES) {
            throw new RequestValidationException("applicationCode accepts at most " + MAX_APPLICATION_CODES + " values");
        }
        if (unassignedOnly && assignedAgent != null && !assignedAgent.isBlank()) {
            throw new RequestValidationException("unassignedOnly cannot be combined with assignedAgent");
        }
        if (createdFrom != null && createdTo != null && !createdFrom.isBefore(createdTo)) {
            throw new RequestValidationException("createdFrom must be before createdTo");
        }
    }

    /**
     * {@code sha256:<hex>} over a canonical, sorted, UTC-normalized
     * representation, so two logically identical filter sets always
     * produce the same fingerprint regardless of set iteration order.
     */
    public String fingerprint() {
        String canonical = "statuses=" + sortedNames(statuses)
            + "|priorities=" + sortedNames(priorities)
            + "|applicationCodes=" + sortedNames(applicationCodes)
            + "|assignedTeams=" + assignedTeams.stream().sorted().collect(Collectors.joining(","))
            + "|assignedAgent=" + (assignedAgent == null ? "" : assignedAgent)
            + "|unassignedOnly=" + unassignedOnly
            + "|slaStates=" + sortedNames(slaStates)
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
