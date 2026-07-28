package dev.opsmind.ticketworkflow.ticket.application.query;

import dev.opsmind.ticketworkflow.ticket.domain.value.ApplicationCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Trusted Support Queue authorization scope (SPEC-TW-005 §5), resolved from
 * verified JWT claims (never from a request parameter). {@code
 * allowedApplicationCodes} is the resource-level authorization boundary,
 * enforced in SQL exactly as Get Ticket's Support view already does
 * (§6). {@code allowedTeamIds} is used to validate the {@code
 * assignedTeam} filter (§7) rather than as a blanket row-level
 * authorization predicate: {@code current_team_id} is not yet an
 * authorization dimension anywhere else in this codebase, and no
 * acceptance scenario requires hiding an application-authorized Ticket
 * that has no team (or a different team) assigned — only requesting an
 * out-of-scope {@code assignedTeam} filter is rejected. {@code
 * allowedTenantIds}, {@code allowedRegions}, and {@code
 * maximumDataClassification} from the spec's illustrative scope model are
 * omitted: no tenant, region, or per-Ticket data-classification column
 * exists anywhere in the current domain model, so there is nothing for
 * them to authorize against yet.
 */
public record SupportQueueScope(
    Set<ApplicationCode> allowedApplicationCodes,
    Set<String> allowedTeamIds
) {

    public SupportQueueScope {
        allowedApplicationCodes = allowedApplicationCodes == null || allowedApplicationCodes.isEmpty()
            ? Set.of() : EnumSet.copyOf(allowedApplicationCodes);
        allowedTeamIds = allowedTeamIds == null ? Set.of() : Set.copyOf(allowedTeamIds);
    }

    public String fingerprint() {
        String canonical = "applicationCodes=" + allowedApplicationCodes.stream().map(Enum::name).sorted().collect(Collectors.joining(","))
            + "|teamIds=" + allowedTeamIds.stream().sorted().collect(Collectors.joining(","));
        return "sha256:" + sha256Hex(canonical);
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
