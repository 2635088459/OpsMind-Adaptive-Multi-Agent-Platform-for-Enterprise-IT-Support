package com.opsmind.policygovernance.api;

import com.opsmind.policygovernance.api.dto.BackfillProcessedEventRequest;
import com.opsmind.policygovernance.api.dto.BackfillProcessedEventResponse;
import com.opsmind.policygovernance.api.dto.ProcessedEventResponse;
import com.opsmind.policygovernance.api.support.GovernanceRequestContext;
import com.opsmind.policygovernance.application.ProcessedEventAdminService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * SPEC-PG-034 (goal: "admin-safe repair flow for governance event
 * replay/backfill"). See {@link ProcessedEventAdminService}'s own javadoc
 * for why this is a separate class from {@code
 * ConsumedEventDeduplicationService}.
 */
@RestController
public class ProcessedEventAdminController {

    private final ProcessedEventAdminService processedEventAdminService;

    public ProcessedEventAdminController(ProcessedEventAdminService processedEventAdminService) {
        this.processedEventAdminService = processedEventAdminService;
    }

    /**
     * "Review": SPEC-PG-014 (11-security §Permission Model: "RBAC decides
     * whether a user can ... view audit") — gated the same as any other
     * read of governance dedup/audit bookkeeping.
     */
    @PreAuthorize("hasAuthority('SCOPE_governance:audit:read')")
    @GetMapping("/api/v1/admin/processed-events")
    public ResponseEntity<List<ProcessedEventResponse>> findByEventId(@RequestParam String eventId) {
        List<ProcessedEventResponse> response = processedEventAdminService.findByEventId(eventId).stream()
            .map(ProcessedEventResponse::from)
            .toList();
        return ResponseEntity.ok(response);
    }

    /**
     * "Admin-safe repair flow ... for replay/backfill": no {@code
     * @PreAuthorize} scope, mirroring {@code OutboxAdminController}'s own
     * precedent for this exact category of admin maintenance endpoint
     * (baseline authenticated actor only).
     */
    @PostMapping("/api/v1/admin/processed-events/{eventId}/{consumerName}:backfill")
    public ResponseEntity<BackfillProcessedEventResponse> backfill(
        @PathVariable String eventId, @PathVariable String consumerName,
        @Valid @RequestBody BackfillProcessedEventRequest request, Authentication authentication, HttpServletRequest httpRequest
    ) {
        processedEventAdminService.backfill(
            eventId, consumerName, GovernanceRequestContext.actorId(authentication), request.reason(),
            GovernanceRequestContext.correlationId(httpRequest)
        );
        return ResponseEntity.ok(new BackfillProcessedEventResponse(eventId, consumerName, true));
    }
}
