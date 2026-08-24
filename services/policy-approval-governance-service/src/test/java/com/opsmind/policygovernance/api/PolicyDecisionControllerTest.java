package com.opsmind.policygovernance.api;

import com.opsmind.policygovernance.application.PolicyDecisionService;
import com.opsmind.policygovernance.application.command.EvaluateDecisionCommand;
import com.opsmind.policygovernance.application.exception.DecisionKeyConflictException;
import com.opsmind.policygovernance.application.exception.PolicyDecisionNotFoundException;
import com.opsmind.policygovernance.domain.decision.DecisionEffect;
import com.opsmind.policygovernance.domain.decision.PolicyDecision;
import com.opsmind.policygovernance.domain.decision.ReasonCode;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import com.opsmind.policygovernance.platform.error.GlobalRestExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PolicyDecisionControllerTest {

    private MockMvc mockMvc;
    private CapturingPolicyDecisionService policyDecisionService;

    @BeforeEach
    void resetService() {
        policyDecisionService = new CapturingPolicyDecisionService();
        mockMvc = MockMvcBuilders.standaloneSetup(new PolicyDecisionController(policyDecisionService))
            .setControllerAdvice(new GlobalRestExceptionHandler())
            .setValidator(validator())
            .build();
        policyDecisionService.reset();
    }

    @Test
    void evaluatesPolicyDecisionAndReturnsGovernanceFact() throws Exception {
        policyDecisionService.nextResult = command -> decision();

        mockMvc.perform(post("/api/v1/policy-decisions:evaluate")
                .header("X-Correlation-Id", "corr-1")
                .header("X-Causation-Id", "cause-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequest()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.policyDecisionId").value("pd-1"))
            .andExpect(jsonPath("$.effect").value("REQUIRE_APPROVAL"))
            .andExpect(jsonPath("$.riskLevel").value("HIGH"))
            .andExpect(jsonPath("$.approvalRequired").value(true))
            .andExpect(jsonPath("$.evaluationFailed").value(false))
            .andExpect(jsonPath("$.reasonCodes[0]").value("HIGH_RISK_REQUIRES_APPROVAL"))
            .andExpect(jsonPath("$.policyVersion").value("4"));

        EvaluateDecisionCommand command = policyDecisionService.lastCommand;
        assertThat(command.decisionKey()).isEqualTo("tool-request-123:risk:v1");
        assertThat(command.inputHash()).isEqualTo("sha256:abc");
        assertThat(command.sourceDomain()).isEqualTo("tool-gateway");
        assertThat(command.sourceRequestId()).isEqualTo("trq-123");
        assertThat(command.ticketId()).isEqualTo("ticket-123");
        assertThat(command.workflowInstanceId()).isEqualTo("wf-123");
        assertThat(command.correlationId()).isEqualTo("corr-1");
        assertThat(command.causationId()).isEqualTo("cause-1");
        assertThat(command.readOnly()).as("SPEC-PG-032: readOnly must flow from the request body through to the command").isTrue();
    }

    @Test
    void rejectsEvaluateRequestWithoutCorrelationId() throws Exception {
        mockMvc.perform(post("/api/v1/policy-decisions:evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequest()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void mapsDecisionKeyConflictToConflictErrorEnvelope() throws Exception {
        policyDecisionService.nextResult = command -> {
            throw new DecisionKeyConflictException("tool-request-123:risk:v1");
        };

        mockMvc.perform(post("/api/v1/policy-decisions:evaluate")
                .header("X-Correlation-Id", "corr-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequest()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("DECISION_KEY_CONFLICT"))
            .andExpect(jsonPath("$.error.correlationId").value("corr-1"));
    }

    @Test
    void findsAPreviouslyEvaluatedDecisionById() throws Exception {
        policyDecisionService.nextFindByIdResult = policyDecisionId -> decision();

        mockMvc.perform(get("/api/v1/policy-decisions/pd-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.policyDecisionId").value("pd-1"))
            .andExpect(jsonPath("$.effect").value("REQUIRE_APPROVAL"));

        assertThat(policyDecisionService.lastFindByIdRequest).isEqualTo("pd-1");
    }

    @Test
    void mapsPolicyDecisionNotFoundToNotFoundErrorEnvelope() throws Exception {
        policyDecisionService.nextFindByIdResult = policyDecisionId -> {
            throw new PolicyDecisionNotFoundException(policyDecisionId);
        };

        mockMvc.perform(get("/api/v1/policy-decisions/does-not-exist"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("POLICY_DECISION_NOT_FOUND"));
    }

    private static String validRequest() {
        return """
            {
              "decisionKey": "tool-request-123:risk:v1",
              "inputHash": "sha256:abc",
              "subjectType": "AGENT",
              "subjectId": "triage-agent",
              "actionType": "TOOL_EXECUTE",
              "readOnly": true,
              "resourceType": "TOOL_CAPABILITY",
              "resourceId": "kubernetes.restartDeployment",
              "tenantId": "tenant-1",
              "sourceDomain": "tool-gateway",
              "sourceRequestId": "trq-123",
              "ticketId": "ticket-123",
              "workflowInstanceId": "wf-123",
              "policyId": "policy-1"
            }
            """;
    }

    private static PolicyDecision decision() {
        return new PolicyDecision(
            "pd-1", "tool-request-123:risk:v1", "sha256:abc",
            "AGENT", "triage-agent", "TOOL_EXECUTE", "TOOL_CAPABILITY", "kubernetes.restartDeployment", "tenant-1",
            "tool-gateway", "trq-123", "ticket-123", "wf-123",
            DecisionEffect.REQUIRE_APPROVAL, RiskLevel.HIGH, true, false,
            List.of(), List.of(ReasonCode.HIGH_RISK_REQUIRES_APPROVAL),
            "policy-1", "4", Instant.parse("2026-01-01T00:00:00Z"), null, false
        );
    }

    static class CapturingPolicyDecisionService extends PolicyDecisionService {

        private EvaluateDecisionCommand lastCommand;
        private Function<EvaluateDecisionCommand, PolicyDecision> nextResult = command -> decision();
        private String lastFindByIdRequest;
        private Function<String, PolicyDecision> nextFindByIdResult = policyDecisionId -> decision();

        CapturingPolicyDecisionService() {
            super(null, null, null, null, null, null);
        }

        @Override
        public PolicyDecision evaluate(EvaluateDecisionCommand command) {
            lastCommand = command;
            return nextResult.apply(command);
        }

        @Override
        public PolicyDecision findById(String policyDecisionId) {
            lastFindByIdRequest = policyDecisionId;
            return nextFindByIdResult.apply(policyDecisionId);
        }

        void reset() {
            lastCommand = null;
            nextResult = command -> decision();
            lastFindByIdRequest = null;
            nextFindByIdResult = policyDecisionId -> decision();
        }
    }

    private static LocalValidatorFactoryBean validator() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return validator;
    }
}
