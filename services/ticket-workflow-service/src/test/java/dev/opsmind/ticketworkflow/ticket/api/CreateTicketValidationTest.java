package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.PublicTicketApiMapper;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.PublicTicketController;
import dev.opsmind.ticketworkflow.ticket.application.port.in.CreateTicketUseCase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PublicTicketController.class)
@Import({SecurityConfiguration.class, PublicTicketApiMapper.class, TestSecurityConfiguration.class})
@Tag("component")
class CreateTicketValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreateTicketUseCase createTicketUseCase;

    @Test
    void shouldRejectBlankTitle() throws Exception {
        performCreate("""
            {"title":"","description":"A valid description.","applicationCode":"HOUSING_PORTAL","source":"PORTAL"}
            """)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectTitleLongerThan200Characters() throws Exception {
        String longTitle = "a".repeat(201);
        performCreate("""
            {"title":"%s","description":"A valid description.","applicationCode":"HOUSING_PORTAL","source":"PORTAL"}
            """.formatted(longTitle))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectBlankDescription() throws Exception {
        performCreate("""
            {"title":"A valid title","description":"","applicationCode":"HOUSING_PORTAL","source":"PORTAL"}
            """)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectUnknownApplicationCode() throws Exception {
        performCreate("""
            {"title":"A valid title","description":"A valid description.","applicationCode":"NOT_A_REAL_APP","source":"PORTAL"}
            """)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectNonPortalSourceForEmployeeActor() throws Exception {
        performCreate("""
            {"title":"A valid title","description":"A valid description.","applicationCode":"HOUSING_PORTAL","source":"EMAIL"}
            """)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldRejectMissingIdempotencyKeyHeader() throws Exception {
        mockMvc.perform(post("/api/v1/tickets")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "user-1"))
                    .authorities(new SimpleGrantedAuthority("SCOPE_tickets:create")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"title":"A valid title","description":"A valid description.","applicationCode":"HOUSING_PORTAL","source":"PORTAL"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    private org.springframework.test.web.servlet.ResultActions performCreate(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/tickets")
            .with(jwt().jwt(jwt -> jwt.claim("sub", "user-1"))
                .authorities(new SimpleGrantedAuthority("SCOPE_tickets:create")))
            .header("Idempotency-Key", "validation-test-key")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }
}
