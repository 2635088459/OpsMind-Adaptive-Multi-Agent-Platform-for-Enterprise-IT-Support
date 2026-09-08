package com.opsmind.policygovernance.api;

import com.opsmind.policygovernance.api.dto.PolicyDetailResponse;
import com.opsmind.policygovernance.api.dto.PolicySummaryResponse;
import com.opsmind.policygovernance.application.PolicyQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read side of the Admin Policy API — lists policies and returns one
 * policy's full version history. Gated by {@code policy:read} (a new client
 * scope alongside the existing {@code policy:draft}/{@code policy:review}/
 * {@code policy:publish}); a 404 for an unknown policyId is handled centrally
 * by {@code GlobalRestExceptionHandler} via {@code PolicyNotFoundException}.
 */
@RestController
public class PolicyQueryController {

    private final PolicyQueryService policyQueryService;

    public PolicyQueryController(PolicyQueryService policyQueryService) {
        this.policyQueryService = policyQueryService;
    }

    @PreAuthorize("hasAuthority('SCOPE_policy:read')")
    @GetMapping("/api/v1/policies")
    public ResponseEntity<List<PolicySummaryResponse>> list() {
        return ResponseEntity.ok(
            policyQueryService.listPolicies().stream().map(PolicySummaryResponse::from).toList()
        );
    }

    @PreAuthorize("hasAuthority('SCOPE_policy:read')")
    @GetMapping("/api/v1/policies/{policyId}")
    public ResponseEntity<PolicyDetailResponse> get(@PathVariable String policyId) {
        return ResponseEntity.ok(
            PolicyDetailResponse.of(
                policyQueryService.getPolicy(policyId),
                policyQueryService.getPolicyVersions(policyId)
            )
        );
    }
}
