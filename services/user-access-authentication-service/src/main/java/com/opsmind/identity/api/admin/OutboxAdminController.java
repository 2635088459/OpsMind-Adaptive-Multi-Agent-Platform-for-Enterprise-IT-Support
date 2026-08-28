package com.opsmind.identity.api.admin;

import com.opsmind.identity.application.dto.OutboxDispatchResponse;
import com.opsmind.identity.application.service.OutboxDispatchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 08-transaction-and-outbox: admin-triggered drain of due {@code PENDING} outbox rows — see {@code OutboxDispatchService}'s own javadoc for why nothing calls this automatically. */
@RestController
public class OutboxAdminController {

    private final OutboxDispatchService outboxDispatchService;

    public OutboxAdminController(OutboxDispatchService outboxDispatchService) {
        this.outboxDispatchService = outboxDispatchService;
    }

    @PostMapping("/internal/identity/v1/admin/outbox/dispatch")
    public ResponseEntity<OutboxDispatchResponse> dispatch() {
        OutboxDispatchService.DrainResult result = outboxDispatchService.publishPending();
        return ResponseEntity.ok(new OutboxDispatchResponse(result.published(), result.retried(), result.failed()));
    }
}
