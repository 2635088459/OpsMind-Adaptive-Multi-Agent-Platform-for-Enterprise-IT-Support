package com.opsmind.policygovernance.api;

import com.opsmind.policygovernance.application.ApprovalService;
import com.opsmind.policygovernance.application.command.CancelApprovalCommand;
import com.opsmind.policygovernance.application.command.DecideApprovalCommand;
import com.opsmind.policygovernance.application.command.RequestApprovalCommand;
import com.opsmind.policygovernance.application.command.RevokeOverrideCommand;
import com.opsmind.policygovernance.application.command.UseOverrideCommand;
import com.opsmind.policygovernance.application.exception.ApprovalAlreadyCancelledException;
import com.opsmind.policygovernance.application.exception.ApprovalAlreadyDecidedException;
import com.opsmind.policygovernance.application.exception.ApprovalRequestNotFoundException;
import com.opsmind.policygovernance.application.exception.DuplicateApprovalRequestException;
import com.opsmind.policygovernance.application.exception.OverrideAlreadyUsedException;
import com.opsmind.policygovernance.domain.approval.ApprovalRequest;
import com.opsmind.policygovernance.domain.approval.ApprovalType;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import com.opsmind.policygovernance.platform.error.GlobalRestExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SPEC-PG-010 added {@code GET /approval-requests/{approvalRequestId}}
 * (05-api-contracts) alongside the real {@code approval.requested.v1}
 * event; SPEC-PG-011 added coverage for the {@code :grant}/{@code :deny}
 * endpoints (previously wired but never exercised at the controller
 * layer). Follows the same standalone-MockMvc pattern as {@code
 * PolicyDecisionControllerTest} (no full Spring context needed).
 */
class ApprovalControllerTest {

    private MockMvc mockMvc;
    private CapturingApprovalService approvalService;

    @BeforeEach
    void resetService() {
        approvalService = new CapturingApprovalService();
        mockMvc = MockMvcBuilders.standaloneSetup(new ApprovalController(approvalService))
            .setControllerAdvice(new GlobalRestExceptionHandler())
            .setValidator(validator())
            .build();
        approvalService.reset();
    }

    @Test
    void createsAnApprovalRequestAndReturnsItsGovernanceFact() throws Exception {
        approvalService.nextRequestResult = command -> approvalRequestFixture();

        mockMvc.perform(post("/api/v1/approval-requests")
                .principal(actor())
                .header("X-Correlation-Id", "corr-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequestBody()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.approvalRequestId").value("ar-1"))
            .andExpect(jsonPath("$.status").value("REQUESTED"));
    }

    @Test
    void mapsDuplicateApprovalRequestToConflictErrorEnvelope() throws Exception {
        approvalService.nextRequestResult = command -> {
            throw new DuplicateApprovalRequestException("rk-1");
        };

        mockMvc.perform(post("/api/v1/approval-requests")
                .principal(actor())
                .header("X-Correlation-Id", "corr-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validRequestBody()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("DUPLICATE_APPROVAL_REQUEST"));
    }

    /**
     * Standalone MockMvc has no Spring Security filter chain to populate
     * {@code request.getUserPrincipal()}, so {@link ApprovalController}
     * methods taking an {@code Authentication} parameter would otherwise
     * always see {@code null} — which {@code GovernanceRequestContext#actorId}
     * treats as a 400, not the endpoint behavior under test.
     * {@code Authentication extends Principal}, so setting it as the
     * request's principal lets Spring MVC's own built-in resolver satisfy
     * the parameter exactly as it would after passing through the real
     * security filter chain.
     */
    private static Authentication actor() {
        return new TestingAuthenticationToken("actor-1", null);
    }

    @Test
    void findsAPreviouslyCreatedApprovalRequestById() throws Exception {
        approvalService.nextFindByIdResult = id -> approvalRequestFixture();

        mockMvc.perform(get("/api/v1/approval-requests/ar-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.approvalRequestId").value("ar-1"))
            .andExpect(jsonPath("$.status").value("REQUESTED"));
    }

    @Test
    void mapsApprovalRequestNotFoundToNotFoundErrorEnvelope() throws Exception {
        approvalService.nextFindByIdResult = id -> {
            throw new ApprovalRequestNotFoundException(id);
        };

        mockMvc.perform(get("/api/v1/approval-requests/does-not-exist"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("APPROVAL_REQUEST_NOT_FOUND"));
    }

    @Test
    void grantsAnApprovalRequestAndReturnsItsUpdatedStatus() throws Exception {
        approvalService.nextGrantResult = command -> approvalRequestFixture();

        mockMvc.perform(post("/api/v1/approval-requests/ar-1:grant")
                .principal(actor())
                .header("X-Correlation-Id", "corr-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(decideRequestBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.approvalRequestId").value("ar-1"));
    }

    @Test
    void deniesAnApprovalRequestAndReturnsItsUpdatedStatus() throws Exception {
        approvalService.nextDenyResult = command -> approvalRequestFixture();

        mockMvc.perform(post("/api/v1/approval-requests/ar-1:deny")
                .principal(actor())
                .header("X-Correlation-Id", "corr-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(decideRequestBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.approvalRequestId").value("ar-1"));
    }

    /** SPEC-PG-011: a decide command reusing a different commandIdempotencyKey than an already-final decision is a conflict. */
    @Test
    void mapsApprovalAlreadyDecidedToConflictErrorEnvelope() throws Exception {
        approvalService.nextGrantResult = command -> {
            throw new ApprovalAlreadyDecidedException("ar-1");
        };

        mockMvc.perform(post("/api/v1/approval-requests/ar-1:grant")
                .principal(actor())
                .header("X-Correlation-Id", "corr-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(decideRequestBody()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("APPROVAL_ALREADY_DECIDED"));
    }

    /** SPEC-PG-012: {@code :cancel} previously had zero MockMvc-level coverage, mirroring SPEC-PG-011's own gap for grant/deny. */
    @Test
    void cancelsAnApprovalRequestAndReturnsItsUpdatedStatus() throws Exception {
        approvalService.nextCancelResult = command -> approvalRequestFixture();

        mockMvc.perform(post("/api/v1/approval-requests/ar-1:cancel")
                .principal(actor())
                .header("X-Correlation-Id", "corr-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(cancelRequestBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.approvalRequestId").value("ar-1"));
    }

    /** SPEC-PG-012: a cancel retry reusing a different commandIdempotencyKey than an already-final cancel is a conflict. */
    @Test
    void mapsApprovalAlreadyCancelledToConflictErrorEnvelope() throws Exception {
        approvalService.nextCancelResult = command -> {
            throw new ApprovalAlreadyCancelledException("ar-1");
        };

        mockMvc.perform(post("/api/v1/approval-requests/ar-1:cancel")
                .principal(actor())
                .header("X-Correlation-Id", "corr-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(cancelRequestBody()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("APPROVAL_ALREADY_CANCELLED"));
    }

    /** SPEC-PG-022: {@code :use} had zero MockMvc-level coverage, mirroring SPEC-PG-012's own gap for cancel. */
    @Test
    void usesAnOverrideAndReturnsItsUpdatedStatus() throws Exception {
        approvalService.nextUseResult = command -> approvalRequestFixture();

        mockMvc.perform(post("/api/v1/approval-requests/ar-1:use")
                .principal(actor())
                .header("X-Correlation-Id", "corr-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(useOrRevokeRequestBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.approvalRequestId").value("ar-1"));
    }

    /** SPEC-PG-022: a use retry reusing a different commandIdempotencyKey than an already-final use is a conflict. */
    @Test
    void mapsOverrideAlreadyUsedToConflictErrorEnvelope() throws Exception {
        approvalService.nextUseResult = command -> {
            throw new OverrideAlreadyUsedException("ar-1");
        };

        mockMvc.perform(post("/api/v1/approval-requests/ar-1:use")
                .principal(actor())
                .header("X-Correlation-Id", "corr-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(useOrRevokeRequestBody()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("OVERRIDE_ALREADY_USED"));
    }

    /** SPEC-PG-022: {@code :revoke} had zero MockMvc-level coverage, mirroring the {@code :use} gap immediately above. */
    @Test
    void revokesAnOverrideAndReturnsItsUpdatedStatus() throws Exception {
        approvalService.nextRevokeResult = command -> approvalRequestFixture();

        mockMvc.perform(post("/api/v1/approval-requests/ar-1:revoke")
                .principal(actor())
                .header("X-Correlation-Id", "corr-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(useOrRevokeRequestBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.approvalRequestId").value("ar-1"));
    }

    private static String useOrRevokeRequestBody() {
        return """
            {
              "sourceRequestId": "src-req-1",
              "requestHash": "hash-1",
              "reason": "exercising the override",
              "commandIdempotencyKey": "cik-1"
            }
            """;
    }

    private static String cancelRequestBody() {
        return """
            {
              "sourceRequestId": "src-req-1",
              "requestHash": "hash-1",
              "reason": "no longer needed",
              "commandIdempotencyKey": "cik-1"
            }
            """;
    }

    private static String decideRequestBody() {
        return """
            {
              "sourceRequestId": "src-req-1",
              "requestHash": "hash-1",
              "reason": "looks fine",
              "commandIdempotencyKey": "cik-1"
            }
            """;
    }

    private static String validRequestBody() {
        return """
            {
              "requestKey": "rk-1",
              "requestHash": "hash-1",
              "sourceDomain": "tool-gateway",
              "sourceRequestId": "src-req-1",
              "approvalType": "TOOL_EXECUTION",
              "riskLevel": "HIGH"
            }
            """;
    }

    private static ApprovalRequest approvalRequestFixture() {
        return ApprovalRequest.requested(
            "ar-1", "rk-1", "hash-1", "tool-gateway", "src-req-1", null, null, "tool-req-1", null,
            null, "requester-1", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH, List.of(),
            Instant.now().plusSeconds(3600), Instant.now()
        );
    }

    static class CapturingApprovalService extends ApprovalService {

        private Function<RequestApprovalCommand, ApprovalRequest> nextRequestResult = command -> approvalRequestFixture();
        private Function<String, ApprovalRequest> nextFindByIdResult = id -> approvalRequestFixture();
        private Function<DecideApprovalCommand, ApprovalRequest> nextGrantResult = command -> approvalRequestFixture();
        private Function<DecideApprovalCommand, ApprovalRequest> nextDenyResult = command -> approvalRequestFixture();

        CapturingApprovalService() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public ApprovalRequest request(RequestApprovalCommand command) {
            return nextRequestResult.apply(command);
        }

        @Override
        public ApprovalRequest findById(String approvalRequestId) {
            return nextFindByIdResult.apply(approvalRequestId);
        }

        @Override
        public ApprovalRequest grant(DecideApprovalCommand command) {
            return nextGrantResult.apply(command);
        }

        @Override
        public ApprovalRequest deny(DecideApprovalCommand command) {
            return nextDenyResult.apply(command);
        }

        private Function<CancelApprovalCommand, ApprovalRequest> nextCancelResult = command -> approvalRequestFixture();
        private Function<UseOverrideCommand, ApprovalRequest> nextUseResult = command -> approvalRequestFixture();
        private Function<RevokeOverrideCommand, ApprovalRequest> nextRevokeResult = command -> approvalRequestFixture();

        @Override
        public ApprovalRequest cancel(CancelApprovalCommand command) {
            return nextCancelResult.apply(command);
        }

        @Override
        public ApprovalRequest use(UseOverrideCommand command) {
            return nextUseResult.apply(command);
        }

        @Override
        public ApprovalRequest revoke(RevokeOverrideCommand command) {
            return nextRevokeResult.apply(command);
        }

        void reset() {
            nextRequestResult = command -> approvalRequestFixture();
            nextFindByIdResult = id -> approvalRequestFixture();
            nextGrantResult = command -> approvalRequestFixture();
            nextDenyResult = command -> approvalRequestFixture();
            nextCancelResult = command -> approvalRequestFixture();
            nextUseResult = command -> approvalRequestFixture();
            nextRevokeResult = command -> approvalRequestFixture();
        }
    }

    private static LocalValidatorFactoryBean validator() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return validator;
    }
}
