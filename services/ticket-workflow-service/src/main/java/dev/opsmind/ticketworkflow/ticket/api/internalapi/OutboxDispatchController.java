package dev.opsmind.ticketworkflow.ticket.api.internalapi;

import dev.opsmind.ticketworkflow.ticket.application.service.OutboxDispatchApplicationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Project-level integration verification (2026-09-01): the admin/external-
 * scheduler entry point into {@link OutboxDispatchApplicationService},
 * mirroring policy-approval-governance-service's own {@code
 * OutboxAdminController} precedent for this exact category of endpoint --
 * baseline authenticated actor only (no {@code @PreAuthorize} scope), per
 * {@code SecurityConfiguration}'s {@code anyRequest().authenticated()}.
 * "Outbox replay": drains due rows on demand, the same work an external
 * scheduler would otherwise wait for -- see that class's own javadoc.
 */
@RestController
public class OutboxDispatchController {

    private final OutboxDispatchApplicationService outboxDispatchApplicationService;

    public OutboxDispatchController(OutboxDispatchApplicationService outboxDispatchApplicationService) {
        this.outboxDispatchApplicationService = outboxDispatchApplicationService;
    }

    @PostMapping("/internal/v1/outbox:dispatch")
    public ResponseEntity<OutboxDispatchResponse> dispatch() {
        OutboxDispatchApplicationService.DrainResult result = outboxDispatchApplicationService.dispatchPending();
        return ResponseEntity.ok(OutboxDispatchResponse.from(result));
    }
}
