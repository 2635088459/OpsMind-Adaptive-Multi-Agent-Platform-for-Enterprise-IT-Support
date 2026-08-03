package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.support.RequestApprovalApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.support.RequestApprovalController;
import dev.opsmind.ticketworkflow.ticket.application.port.in.RequestApprovalUseCase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SPEC-TW-014 API contract: headers, path shape, and request-body Bean Validation. Mirrors {@code RequestUserInputValidationTest}. */
@WebMvcTest(RequestApprovalController.class)
@Import({SecurityConfiguration.class, RequestApprovalApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class RequestApprovalValidationTest {

    private static final UUID TICKET_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String VALID_BODY = """
        {"workflowId":"wf-9000","actionId":"act-100","actionType":"RESET_MFA","riskLevel":"HIGH",\
        "riskContext":{"targetSystem":"identity"},"reason":"MFA reset requires approval before execution."}
        """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RequestApprovalUseCase requestApprovalUseCase;

    private MockHttpServletRequestBuilder validRequest() {
        return post("/api/v1/tickets/" + TICKET_ID + "/approval-requests")
            .with(jwt().jwt(jwt -> jwt.claim("sub", "sam.support").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:request-approval")))
            .header("If-Match", "\"20\"")
            .header("Idempotency-Key", "key-1")
            .contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    void shouldRejectMissingIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/approval-requests")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "sam.support").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:request-approval")))
                .header("If-Match", "\"20\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(requestApprovalUseCase, never()).requestApproval(any());
    }

    @Test
    void shouldReturn428WhenIfMatchIsMissing() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/approval-requests")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "sam.support").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:request-approval")))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().is(428))
            .andExpect(jsonPath("$.error.code").value("PRECONDITION_REQUIRED"));
        verify(requestApprovalUseCase, never()).requestApproval(any());
    }

    @Test
    void shouldReturn400WhenIfMatchIsNotANumber() throws Exception {
        mockMvc.perform(validRequest().header("If-Match", "not-a-number").content(VALID_BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldReturn400ForAMalformedTicketIdPathSegment() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/not-a-uuid/approval-requests")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "sam.support").claim("actor_type", "IT_SUPPORT").claim("scope", "ticket:request-approval")))
                .header("If-Match", "\"20\"")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectAMissingWorkflowId() throws Exception {
        mockMvc.perform(validRequest().content(
                "{\"actionId\":\"act-100\",\"actionType\":\"RESET_MFA\",\"riskLevel\":\"HIGH\",\"riskContext\":{\"a\":\"b\"}}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectAMissingActionId() throws Exception {
        mockMvc.perform(validRequest().content(
                "{\"workflowId\":\"wf-9000\",\"actionType\":\"RESET_MFA\",\"riskLevel\":\"HIGH\",\"riskContext\":{\"a\":\"b\"}}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectAMissingActionType() throws Exception {
        mockMvc.perform(validRequest().content(
                "{\"workflowId\":\"wf-9000\",\"actionId\":\"act-100\",\"riskLevel\":\"HIGH\",\"riskContext\":{\"a\":\"b\"}}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectAMissingRiskLevel() throws Exception {
        mockMvc.perform(validRequest().content(
                "{\"workflowId\":\"wf-9000\",\"actionId\":\"act-100\",\"actionType\":\"RESET_MFA\",\"riskContext\":{\"a\":\"b\"}}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectAnInvalidRiskLevel() throws Exception {
        mockMvc.perform(validRequest().content(
                "{\"workflowId\":\"wf-9000\",\"actionId\":\"act-100\",\"actionType\":\"RESET_MFA\",\"riskLevel\":\"EXTREME\",\"riskContext\":{\"a\":\"b\"}}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectAMissingRiskContext() throws Exception {
        mockMvc.perform(validRequest().content(
                "{\"workflowId\":\"wf-9000\",\"actionId\":\"act-100\",\"actionType\":\"RESET_MFA\",\"riskLevel\":\"HIGH\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectAnEmptyRiskContext() throws Exception {
        mockMvc.perform(validRequest().content(
                "{\"workflowId\":\"wf-9000\",\"actionId\":\"act-100\",\"actionType\":\"RESET_MFA\",\"riskLevel\":\"HIGH\",\"riskContext\":{}}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectUnknownFieldsIncludingActorImpersonationFields() throws Exception {
        mockMvc.perform(validRequest().content(
                "{\"workflowId\":\"wf-9000\",\"actionId\":\"act-100\",\"actionType\":\"RESET_MFA\",\"riskLevel\":\"HIGH\",\"riskContext\":{\"a\":\"b\"},\"approvalId\":\"appr-spoofed\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(requestApprovalUseCase, never()).requestApproval(any());
    }

    @Test
    void shouldReturn400ValidationErrorForAMalformedBody() throws Exception {
        mockMvc.perform(validRequest().content("{ not json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
}
