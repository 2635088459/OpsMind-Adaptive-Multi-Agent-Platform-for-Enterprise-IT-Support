package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.AddTicketMessageFixtures;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.PublicTicketMessageApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.PublicTicketMessageController;
import dev.opsmind.ticketworkflow.ticket.application.port.in.AddTicketMessageUseCase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import dev.opsmind.ticketworkflow.ticket.application.observability.TicketTelemetry;
import dev.opsmind.ticketworkflow.ticket.application.policy.SecretDetectionAuditRecorder;
import dev.opsmind.ticketworkflow.ticket.application.policy.SecretDetectionPolicy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SPEC-TW-004 §7/§17: content, idempotency key, and Support messageType validation. */
@WebMvcTest(PublicTicketMessageController.class)
@Import({SecurityConfiguration.class, PublicTicketMessageApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class AddTicketMessageValidationTest {

    private static final UUID TICKET_ID = AddTicketMessageFixtures.DEFAULT_TICKET_ID;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AddTicketMessageUseCase addTicketMessageUseCase;

    @MockitoBean
    private SecretDetectionPolicy secretDetectionPolicy;

    @MockitoBean
    private SecretDetectionAuditRecorder secretDetectionAuditRecorder;

    @MockitoBean
    private TicketTelemetry ticketTelemetry;

    @Test
    void shouldRejectBlankContent() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/messages")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "employee-123").claim("actor_type", "EMPLOYEE"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:message:self")))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"content":"   "}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectContentOverMaxLength() throws Exception {
        String tooLong = "a".repeat(8001);
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/messages")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "employee-123").claim("actor_type", "EMPLOYEE"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:message:self")))
                .header("Idempotency-Key", "key-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"" + tooLong + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectMissingIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/messages")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "employee-123").claim("actor_type", "EMPLOYEE"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:message:self")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"content":"valid content"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectSupportRequestMissingMessageType() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/messages")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:message:public")))
                .header("Idempotency-Key", "key-3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"content":"valid content"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectSupportRequestWithInvalidMessageType() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/messages")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:message:public")))
                .header("Idempotency-Key", "key-4")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"content":"valid content","messageType":"NOT_A_REAL_TYPE"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectSupportRequestUsingEmployeeOnlyMessageType() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/messages")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "support-100").claim("actor_type", "IT_SUPPORT"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:message:public")))
                .header("Idempotency-Key", "key-5")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"content":"valid content","messageType":"PUBLIC_REQUESTER_MESSAGE"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    /**
     * SPEC-TW-035 hardening: secret-like content is now distinguished from
     * an ordinary shape violation and rejected with {@code 403
     * SECRET_DETECTED} (previously an undifferentiated {@code 400
     * VALIDATION_ERROR}), still never echoing the matched text.
     */
    @Test
    void shouldRejectPrivateKeyContentWithoutEchoingIt() throws Exception {
        String bodyWithKey = """
            {"content":"here is my key -----BEGIN RSA PRIVATE KEY----- MIIEow== -----END RSA PRIVATE KEY-----"}
            """;

        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/messages")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "employee-123").claim("actor_type", "EMPLOYEE"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:message:self")))
                .header("Idempotency-Key", "key-6")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithKey))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("SECRET_DETECTED"))
            .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("BEGIN RSA PRIVATE KEY"))));
    }
}
