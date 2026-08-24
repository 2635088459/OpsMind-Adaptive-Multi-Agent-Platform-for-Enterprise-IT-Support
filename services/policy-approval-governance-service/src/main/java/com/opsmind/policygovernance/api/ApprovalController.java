package com.opsmind.policygovernance.api;

import com.opsmind.policygovernance.api.dto.ApprovalRequestResponse;
import com.opsmind.policygovernance.api.dto.CancelApprovalRequest;
import com.opsmind.policygovernance.api.dto.ConstraintDto;
import com.opsmind.policygovernance.api.dto.DecideApprovalRequest;
import com.opsmind.policygovernance.api.dto.RequestApprovalRequest;
import com.opsmind.policygovernance.api.dto.RevokeOverrideRequest;
import com.opsmind.policygovernance.api.dto.UseOverrideRequest;
import com.opsmind.policygovernance.api.support.GovernanceRequestContext;
import com.opsmind.policygovernance.application.ApprovalService;
import com.opsmind.policygovernance.application.command.CancelApprovalCommand;
import com.opsmind.policygovernance.application.command.DecideApprovalCommand;
import com.opsmind.policygovernance.application.command.RequestApprovalCommand;
import com.opsmind.policygovernance.application.command.RevokeOverrideCommand;
import com.opsmind.policygovernance.application.command.UseOverrideCommand;
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
            request.ticketId(), request.workflowInstanceId(), request.toolRequestId(), request.executorId(), request.policyDecisionId(),
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
        CancelApprovalCommand command = new CancelApprovalCommand(
            approvalRequestId, request.sourceRequestId(), request.requestHash(),
            GovernanceRequestContext.actorId(authentication), request.reason(),
            GovernanceRequestContext.correlationId(httpRequest), request.commandIdempotencyKey(),
            GovernanceRequestContext.causationId(httpRequest)
        );
        ApprovalRequest cancelled = approvalService.cancel(command);
        return ResponseEntity.ok(ApprovalRequestResponse.from(cancelled));
    }

    /** SPEC-PG-022 (04-use-cases §UC-PG-006): mark an approved {@code POLICY_OVERRIDE} request as actually exercised. */
    @PostMapping("/api/v1/approval-requests/{approvalRequestId}:use")
    public ResponseEntity<ApprovalRequestResponse> use(
        @PathVariable String approvalRequestId, @Valid @RequestBody UseOverrideRequest request,
        Authentication authentication, HttpServletRequest httpRequest
    ) {
        UseOverrideCommand command = new UseOverrideCommand(
            approvalRequestId, request.sourceRequestId(), request.requestHash(),
            GovernanceRequestContext.actorId(authentication), request.reason(),
            GovernanceRequestContext.correlationId(httpRequest), request.commandIdempotencyKey(),
            GovernanceRequestContext.causationId(httpRequest)
        );
        ApprovalRequest used = approvalService.use(command);
        return ResponseEntity.ok(ApprovalRequestResponse.from(used));
    }

    /** SPEC-PG-022 (04-use-cases §UC-PG-006): withdraw an approved {@code POLICY_OVERRIDE} request before it is used. */
    @PostMapping("/api/v1/approval-requests/{approvalRequestId}:revoke")
    public ResponseEntity<ApprovalRequestResponse> revoke(
        @PathVariable String approvalRequestId, @Valid @RequestBody RevokeOverrideRequest request,
        Authentication authentication, HttpServletRequest httpRequest
    ) {
        RevokeOverrideCommand command = new RevokeOverrideCommand(
            approvalRequestId, request.sourceRequestId(), request.requestHash(),
            GovernanceRequestContext.actorId(authentication), request.reason(),
            GovernanceRequestContext.correlationId(httpRequest), request.commandIdempotencyKey(),
            GovernanceRequestContext.causationId(httpRequest)
        );
        ApprovalRequest revoked = approvalService.revoke(command);
        return ResponseEntity.ok(ApprovalRequestResponse.from(revoked));
    }

    private DecideApprovalCommand decideCommand(
        String approvalRequestId, DecideApprovalRequest request, Authentication authentication, HttpServletRequest httpRequest
    ) {
        return new DecideApprovalCommand(
            approvalRequestId, request.sourceRequestId(), request.requestHash(),
            GovernanceRequestContext.actorId(authentication), request.reason(),
            toDomainConstraints(request.conditions()), GovernanceRequestContext.correlationId(httpRequest),
            request.commandIdempotencyKey(), request.sessionId(), request.deviceId(), request.stepUpVerified(),
            GovernanceRequestContext.causationId(httpRequest)
        );
    }

    private List<com.opsmind.policygovernance.domain.decision.Constraint> toDomainConstraints(List<ConstraintDto> dtos) {
        return dtos == null ? List.of() : dtos.stream().map(ConstraintDto::toDomain).toList();
    }
}
