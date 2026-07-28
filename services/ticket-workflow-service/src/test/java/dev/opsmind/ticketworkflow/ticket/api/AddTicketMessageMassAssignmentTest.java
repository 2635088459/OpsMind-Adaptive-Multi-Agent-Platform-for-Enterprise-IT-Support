package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.AddTicketMessageFixtures;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.PublicTicketMessageApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.PublicTicketMessageController;
import dev.opsmind.ticketworkflow.ticket.application.port.in.AddTicketMessageUseCase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SPEC-TW-004 §4/§21: a client cannot set messageType, visibility, author,
 * ID, version, or internal metadata. Unknown fields fail Jackson's
 * fail-on-unknown-properties before the DTO is even constructed;
 * {@code messageType} is a known, shared field so the Employee-specific
 * rejection is explicit application logic (covered separately).
 */
@WebMvcTest(PublicTicketMessageController.class)
@Import({SecurityConfiguration.class, PublicTicketMessageApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class AddTicketMessageMassAssignmentTest {

    private static final UUID TICKET_ID = AddTicketMessageFixtures.DEFAULT_TICKET_ID;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AddTicketMessageUseCase addTicketMessageUseCase;

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"content\":\"valid content\",\"visibility\":\"INTERNAL\"}",
        "{\"content\":\"valid content\",\"authorId\":\"someone-else\"}",
        "{\"content\":\"valid content\",\"authorType\":\"IT_SUPPORT\"}",
        "{\"content\":\"valid content\",\"messageId\":\"018f0f1e-7b31-7a00-8f42-31f9b25b1a91\"}",
        "{\"content\":\"valid content\",\"version\":5}",
        "{\"content\":\"valid content\",\"createdAt\":\"2020-01-01T00:00:00Z\"}",
        "{\"content\":\"valid content\",\"ticketId\":\"018f0f1e-7b31-7a00-8f42-31f9b25b1a91\"}"
    })
    void shouldRejectUnknownFieldsAsInvalid(String body) throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/messages")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "employee-123").claim("actor_type", "EMPLOYEE"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:message:self")))
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        verify(addTicketMessageUseCase, never()).addMessage(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldRejectEmployeeInjectingMessageType() throws Exception {
        mockMvc.perform(post("/api/v1/tickets/" + TICKET_ID + "/messages")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "employee-123").claim("actor_type", "EMPLOYEE"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:message:self")))
                .header("Idempotency-Key", "key-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"content":"valid content","messageType":"INTERNAL_SUPPORT_NOTE"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        verify(addTicketMessageUseCase, never()).addMessage(org.mockito.ArgumentMatchers.any());
    }
}
