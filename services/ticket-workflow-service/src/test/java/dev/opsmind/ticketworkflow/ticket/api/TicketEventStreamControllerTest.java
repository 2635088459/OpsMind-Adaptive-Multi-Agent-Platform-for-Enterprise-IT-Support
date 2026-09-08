package dev.opsmind.ticketworkflow.ticket.api;

import dev.opsmind.ticketworkflow.configuration.SecurityConfiguration;
import dev.opsmind.ticketworkflow.support.TestSecurityConfiguration;
import dev.opsmind.ticketworkflow.ticket.api.publicapi.TicketEventStreamController;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketAuthorizationException;
import dev.opsmind.ticketworkflow.ticket.application.exception.TicketNotFoundException;
import dev.opsmind.ticketworkflow.ticket.application.port.in.GetTicketUseCase;
import dev.opsmind.ticketworkflow.ticket.infrastructure.sse.TicketEventBroadcaster;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Component-level companion to {@link TicketEventStreamIT}: this test slice
 * cannot exercise the real {@code ?token=} query-parameter bearer
 * resolution or a real committed broadcast (both need the real filter chain
 * and a real transaction — see that IT's own docstring), but it does verify
 * this controller's own authorization short-circuit and response shape
 * quickly and without Testcontainers.
 */
@WebMvcTest(TicketEventStreamController.class)
@Import({SecurityConfiguration.class, TestSecurityConfiguration.class})
@Tag("component")
class TicketEventStreamControllerTest {

    private static final UUID TICKET_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GetTicketUseCase getTicketUseCase;

    @MockitoBean
    private TicketEventBroadcaster broadcaster;

    @Test
    void shouldOpenAnEventStreamWhenTheActorIsAuthorized() throws Exception {
        SseEmitter emitter = new SseEmitter();
        when(broadcaster.subscribe(any())).thenReturn(emitter);

        MvcResult mvcResult = mockMvc.perform(get("/api/v1/tickets/" + TICKET_ID + "/events")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "employee-123").claim("actor_type", "EMPLOYEE"))))
            .andExpect(request().asyncStarted())
            .andReturn();

        // A real subscriber's SseEmitter only ever completes on client
        // disconnect/timeout/broadcaster push, none of which this slice
        // fakes -- MockMvc's own async dispatch machinery otherwise waits
        // for exactly that completion, so this test supplies it directly
        // rather than asserting on a real push (that is TicketEventStreamIT's
        // own job, against a real transaction and a real broadcaster).
        emitter.complete();

        mockMvc.perform(asyncDispatch(mvcResult))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.valueOf("text/event-stream")));
    }

    @Test
    void shouldRejectAnActorMissingTheRequiredScope() throws Exception {
        when(getTicketUseCase.get(any())).thenThrow(new TicketAuthorizationException("tickets:read:self"));

        mockMvc.perform(get("/api/v1/tickets/" + TICKET_ID + "/events")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "employee-123").claim("actor_type", "EMPLOYEE"))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void shouldReturn404ForATicketThisActorMayNotSee() throws Exception {
        when(getTicketUseCase.get(any())).thenThrow(new TicketNotFoundException());

        mockMvc.perform(get("/api/v1/tickets/" + TICKET_ID + "/events")
                .with(jwt().jwt(jwt -> jwt.claim("sub", "employee-123").claim("actor_type", "EMPLOYEE"))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("TICKET_NOT_FOUND"));
    }

    @Test
    void shouldRejectUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/api/v1/tickets/" + TICKET_ID + "/events"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }
}
