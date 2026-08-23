package com.opsmind.policygovernance.api;

import com.opsmind.policygovernance.api.dto.ApprovalRequestResponse;
import com.opsmind.policygovernance.api.dto.CancelApprovalRequest;
import com.opsmind.policygovernance.api.dto.ConstraintDto;
import com.opsmind.policygovernance.api.dto.DecideApprovalRequest;
import com.opsmind.policygovernance.api.dto.RequestApprovalRequest;
import com.opsmind.policygovernance.api.support.GovernanceRequestContext;
import com.opsmind.policygovernance.application.ApprovalService;
import com.opsmind.policygovernance.application.command.DecideApprovalCommand;
import com.opsmind.policygovernance.application.command.RequestApprovalCommand;
import com.opsmind.policygovernance.domain.approval.ApprovalRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** UC-PG-002/003/004: create, grant, deny, and cancel approval requests. */
@RestController
public class ApprovalController {

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @PostMapping("/api/v1/approval-requests")
    public ResponseEntity<ApprovalRequestResponse> request(
        @Valid @RequestBody RequestApprovalRequest request, Authentication authentication, HttpServletRequest httpRequest
    ) {
        List<com.opsmind.policygovernance.domain.decision.Constraint> constraints = toDomainConstraints(request.constraints());
        RequestApprovalCommand command = new RequestApprovalCommand(
            request.requestKey(), request.requestHash(), request.sourceDomain(), request.sourceRequestId(),
            request.ticketId(), request.workflowInstanceId(), request.toolRequestId(), request.policyDecisionId(),
            GovernanceRequestContext.actorId(authentication), request.approvalType(), request.riskLevel(),
            constraints, request.expiresAt(),
            GovernanceRequestContext.correlationId(httpRequest), GovernanceRequestContext.causationId(httpRequest)
        );
        ApprovalRequest saved = approvalService.request(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApprovalRequestResponse.from(saved));
    }

    /** 05-api-contracts: {@code GET /approval-requests/{approvalRequestId}}. */
    @GetMapping("/api/v1/approval-requests/{approvalRequestId}")
    public ResponseEntity<ApprovalRequestResponse> findById(@PathVariable String approvalRequestId) {
        ApprovalRequest request = approvalService.findById(approvalRequestId);
        return ResponseEntity.ok(ApprovalRequestResponse.from(request));
    }

    @PostMapping("/api/v1/approval-requests/{approvalRequestId}:grant")
    public ResponseEntity<ApprovalRequestResponse> grant(
        @PathVariable String approvalRequestId, @Valid @RequestBody DecideApprovalRequest request,
        Authentication authentication, HttpServletRequest httpRequest
    ) {
        DecideApprovalCommand command = decideCommand(approvalRequestId, request, authentication, httpRequest);
        return ResponseEntity.ok(ApprovalRequestResponse.from(approvalService.grant(command)));
    }

    @PostMapping("/api/v1/approval-requests/{approvalRequestId}:deny")
    public ResponseEntity<ApprovalRequestResponse> deny(
        @PathVariable String approvalRequestId, @Valid @RequestBody DecideApprovalRequest request,
        Authentication authentication, HttpServletRequest httpRequest
    ) {
        DecideApprovalCommand command = decideCommand(approvalRequestId, request, authentication, httpRequest);
        return ResponseEntity.ok(ApprovalRequestResponse.from(approvalService.deny(command)));
    }

    @PostMapping("/api/v1/approval-requests/{approvalRequestId}:cancel")
    public ResponseEntity<ApprovalRequestResponse> cancel(
        @PathVariable String approvalRequestId, @Valid @RequestBody CancelApprovalRequest request,
        Authentication authentication, HttpServletRequest httpRequest
    ) {
        ApprovalRequest cancelled = approvalService.cancel(
            approvalRequestId, GovernanceRequestContext.actorId(authentication), request.reason(),
            GovernanceRequestContext.correlationId(httpRequest)
        );
        return ResponseEntity.ok(ApprovalRequestResponse.from(cancelled));
    }

    private DecideApprovalCommand decideCommand(
        String approvalRequestId, DecideApprovalRequest request, Authentication authentication, HttpServletRequest httpRequest
    ) {
        return new DecideApprovalCommand(
            approvalRequestId, request.sourceRequestId(), request.requestHash(),
            GovernanceRequestContext.actorId(authentication), request.reason(),
            toDomainConstraints(request.conditions()), GovernanceRequestContext.correlationId(httpRequest),
            request.commandIdempotencyKey()
        );
    }

    private List<com.opsmind.policygovernance.domain.decision.Constraint> toDomainConstraints(List<ConstraintDto> dtos) {
        return dtos == null ? List.of() : dtos.stream().map(ConstraintDto::toDomain).toList();
    }
}
