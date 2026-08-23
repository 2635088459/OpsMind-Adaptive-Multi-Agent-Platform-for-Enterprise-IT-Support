package com.opsmind.policygovernance.api;

import com.opsmind.policygovernance.api.dto.EvaluateDecisionRequest;
import com.opsmind.policygovernance.api.dto.PolicyDecisionResponse;
import com.opsmind.policygovernance.api.support.GovernanceRequestContext;
import com.opsmind.policygovernance.application.PolicyDecisionService;
import com.opsmind.policygovernance.application.command.EvaluateDecisionCommand;
import com.opsmind.policygovernance.domain.decision.PolicyDecision;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * UC-PG-001. Returns a governance fact only — INV-PG-001 forbids this
 * endpoint (or anything it calls) from executing tools or mutating Ticket/
 * Workflow/Memory state, which the architecture tests enforce.
 */
@RestController
public class PolicyDecisionController {

    private final PolicyDecisionService policyDecisionService;

    public PolicyDecisionController(PolicyDecisionService policyDecisionService) {
        this.policyDecisionService = policyDecisionService;
    }

    @PostMapping("/api/v1/policy-decisions:evaluate")
    public ResponseEntity<PolicyDecisionResponse> evaluate(@Valid @RequestBody EvaluateDecisionRequest request, HttpServletRequest httpRequest) {
        EvaluateDecisionCommand command = new EvaluateDecisionCommand(
            request.decisionKey(), request.inputHash(), request.subjectType(), request.subjectId(),
            request.actionType(), request.resourceType(), request.resourceId(), request.tenantId(),
            request.sourceDomain(), request.sourceRequestId(), request.ticketId(), request.workflowInstanceId(),
            request.policyId(), GovernanceRequestContext.correlationId(httpRequest), GovernanceRequestContext.causationId(httpRequest)
        );
        PolicyDecision decision = policyDecisionService.evaluate(command);
        return ResponseEntity.status(HttpStatus.OK).body(PolicyDecisionResponse.from(decision));
    }

    /** 05-api-contracts: {@code GET /policy-decisions/{policyDecisionId}} re-fetches an already-evaluated snapshot. */
    @GetMapping("/api/v1/policy-decisions/{policyDecisionId}")
    public ResponseEntity<PolicyDecisionResponse> findById(@PathVariable String policyDecisionId) {
        PolicyDecision decision = policyDecisionService.findById(policyDecisionId);
        return ResponseEntity.ok(PolicyDecisionResponse.from(decision));
    }
}
