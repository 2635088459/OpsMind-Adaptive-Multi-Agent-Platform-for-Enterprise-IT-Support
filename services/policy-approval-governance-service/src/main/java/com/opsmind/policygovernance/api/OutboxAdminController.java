package com.opsmind.policygovernance.api;

import com.opsmind.policygovernance.api.dto.OutboxDispatchResponse;
import com.opsmind.policygovernance.api.dto.OutboxEventResponse;
import com.opsmind.policygovernance.api.dto.RequeueOutboxEventRequest;
import com.opsmind.policygovernance.api.support.GovernanceRequestContext;
import com.opsmind.policygovernance.application.OutboxAdminService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * SPEC-PG-024 (10-failure-handling §Recovery): the admin/external-scheduler
 * entry points for outbox replay and poison repair — {@link
 * com.opsmind.policygovernance.application.OutboxAdminService}'s own
 * javadoc has the full reasoning. No {@code @PreAuthorize} scope, mirroring
 * {@link ApprovalExpiryController}'s own precedent for this exact category
 * of admin/scheduler-triggered maintenance endpoint (baseline authenticated
 * actor only, per {@code SecurityConfig}'s {@code anyRequest().authenticated()}).
 */
@RestController
public class OutboxAdminController {

    private final OutboxAdminService outboxAdminService;

    public OutboxAdminController(OutboxAdminService outboxAdminService) {
        this.outboxAdminService = outboxAdminService;
    }

    /** "Outbox replay": drains due PENDING rows on demand, the same work an external scheduler would otherwise wait for. */
    @PostMapping("/api/v1/admin/outbox:dispatch")
    public ResponseEntity<OutboxDispatchResponse> dispatch() {
        return ResponseEntity.ok(OutboxDispatchResponse.from(outboxAdminService.dispatchPending()));
    }

    /** "Poison repair": gives a dead-lettered (FAILED) row a fresh retry budget. */
    @PostMapping("/api/v1/admin/outbox/{outboxId}:requeue")
    public ResponseEntity<OutboxEventResponse> requeue(
        @PathVariable String outboxId, @Valid @RequestBody RequeueOutboxEventRequest request,
        Authentication authentication, HttpServletRequest httpRequest
    ) {
        var requeued = outboxAdminService.requeue(
            outboxId, GovernanceRequestContext.actorId(authentication), request.reason(),
            GovernanceRequestContext.correlationId(httpRequest)
        );
        return ResponseEntity.ok(OutboxEventResponse.from(requeued));
    }
}
