package com.opsmind.policygovernance.api;

import com.opsmind.policygovernance.api.dto.DraftPolicyRequest;
import com.opsmind.policygovernance.api.dto.PolicyRuleDto;
import com.opsmind.policygovernance.api.dto.PolicyVersionResponse;
import com.opsmind.policygovernance.api.dto.PublishPolicyVersionRequest;
import com.opsmind.policygovernance.api.support.GovernanceRequestContext;
import com.opsmind.policygovernance.application.PolicyAdminService;
import com.opsmind.policygovernance.application.command.DraftPolicyCommand;
import com.opsmind.policygovernance.domain.policy.PolicyVersion;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** UC-PG-005: draft -> review -> publish -> deprecate. api-contract: "Admin Policy API requires reviewer/publisher separation of duties." */
@RestController
public class PolicyAdminController {

    private final PolicyAdminService policyAdminService;

    public PolicyAdminController(PolicyAdminService policyAdminService) {
        this.policyAdminService = policyAdminService;
    }

    @PostMapping("/api/v1/policies:draft")
    public ResponseEntity<PolicyVersionResponse> draft(
        @Valid @RequestBody DraftPolicyRequest request, Authentication authentication, HttpServletRequest httpRequest
    ) {
        List<com.opsmind.policygovernance.domain.policy.PolicyRule> rules = request.rules() == null
            ? List.of() : request.rules().stream().map(PolicyRuleDto::toDomain).toList();
        DraftPolicyCommand command = new DraftPolicyCommand(
            request.policyId(), request.policyName(), request.scope(), rules,
            GovernanceRequestContext.actorId(authentication), GovernanceRequestContext.correlationId(httpRequest)
        );
        PolicyVersion saved = policyAdminService.draft(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(PolicyVersionResponse.from(saved));
    }

    @PostMapping("/api/v1/policy-versions/{policyVersionId}:review")
    public ResponseEntity<PolicyVersionResponse> review(
        @PathVariable String policyVersionId, Authentication authentication, HttpServletRequest httpRequest
    ) {
        PolicyVersion reviewed = policyAdminService.review(
            policyVersionId, GovernanceRequestContext.actorId(authentication), GovernanceRequestContext.correlationId(httpRequest)
        );
        return ResponseEntity.ok(PolicyVersionResponse.from(reviewed));
    }

    /** SPEC-PG-014 (11-security §Permission Model): "RBAC decides whether a user can ... publish policy." */
    @PreAuthorize("hasAuthority('SCOPE_policy:publish')")
    @PostMapping("/api/v1/policy-versions/{policyVersionId}:publish")
    public ResponseEntity<PolicyVersionResponse> publish(
        @PathVariable String policyVersionId, @RequestBody(required = false) PublishPolicyVersionRequest request,
        Authentication authentication, HttpServletRequest httpRequest
    ) {
        var effectiveFrom = request == null ? null : request.effectiveFrom();
        PolicyVersion published = policyAdminService.publish(
            policyVersionId, GovernanceRequestContext.actorId(authentication), effectiveFrom,
            GovernanceRequestContext.correlationId(httpRequest)
        );
        return ResponseEntity.ok(PolicyVersionResponse.from(published));
    }

    @PostMapping("/api/v1/policy-versions/{policyVersionId}:deprecate")
    public ResponseEntity<PolicyVersionResponse> deprecate(
        @PathVariable String policyVersionId, Authentication authentication, HttpServletRequest httpRequest
    ) {
        PolicyVersion deprecated = policyAdminService.deprecate(
            policyVersionId, GovernanceRequestContext.actorId(authentication), GovernanceRequestContext.correlationId(httpRequest)
        );
        return ResponseEntity.ok(PolicyVersionResponse.from(deprecated));
    }
}
