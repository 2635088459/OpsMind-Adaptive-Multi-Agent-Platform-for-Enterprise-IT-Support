package com.opsmind.policygovernance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Policy Approval Governance service (domain 06).
 *
 * <p>Per {@code docs/low-level-design/domains/06-policy-approval-governance/02-business-invariants}
 * INV-PG-001, this service produces governance facts only: policy decisions and
 * approval outcomes. It never executes tools, mutates Ticket/Workflow state, or
 * writes Memory content — see {@link com.opsmind.policygovernance.domain.shared}
 * and the {@code architecture} test package for the enforced boundary.
 */
@SpringBootApplication
public class PolicyApprovalGovernanceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PolicyApprovalGovernanceApplication.class, args);
    }
}
