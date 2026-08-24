package com.opsmind.policygovernance.api;

import com.opsmind.policygovernance.application.OutboxAdminService;
import com.opsmind.policygovernance.application.OutboxDispatchService;
import com.opsmind.policygovernance.application.exception.OutboxEventNotFailedException;
import com.opsmind.policygovernance.application.exception.OutboxEventNotFoundException;
import com.opsmind.policygovernance.application.model.OutboxEventRecord;
import com.opsmind.policygovernance.application.model.OutboxEventStatus;
import com.opsmind.policygovernance.platform.error.GlobalRestExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SPEC-PG-024: {@code :dispatch}/{@code :requeue} had zero MockMvc-level
 * coverage, mirroring the same gap SPEC-PG-011/012/022 each closed for
 * their own first-time controller endpoints.
 */
class OutboxAdminControllerTest {

    private MockMvc mockMvc;
    private OutboxAdminService outboxAdminService;

    @BeforeEach
    void setUp() {
        outboxAdminService = Mockito.mock(OutboxAdminService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new OutboxAdminController(outboxAdminService))
            .setControllerAdvice(new GlobalRestExceptionHandler())
            .setValidator(validator())
            .build();
    }

    private static Authentication actor() {
        return new TestingAuthenticationToken("admin-1", null);
    }

    @Test
    void dispatchDrainsPendingRowsAndReturnsTheDrainResult() throws Exception {
        when(outboxAdminService.dispatchPending()).thenReturn(new OutboxDispatchService.DrainResult(2, 1, 0));

        mockMvc.perform(post("/api/v1/admin/outbox:dispatch"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.published").value(2))
            .andExpect(jsonPath("$.retried").value(1))
            .andExpect(jsonPath("$.deadLettered").value(0));
    }

    @Test
    void requeueResetsADeadLetteredRowAndReturnsItsUpdatedStatus() throws Exception {
        when(outboxAdminService.requeue(anyString(), anyString(), anyString(), any())).thenReturn(outboxEventFixture());

        mockMvc.perform(post("/api/v1/admin/outbox/outbox-1:requeue")
                .principal(actor())
                .header("X-Correlation-Id", "corr-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requeueRequestBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outboxId").value("outbox-1"))
            .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void mapsOutboxEventNotFoundToNotFoundErrorEnvelope() throws Exception {
        when(outboxAdminService.requeue(anyString(), anyString(), anyString(), any())).thenThrow(new OutboxEventNotFoundException("outbox-1"));

        mockMvc.perform(post("/api/v1/admin/outbox/outbox-1:requeue")
                .principal(actor())
                .header("X-Correlation-Id", "corr-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requeueRequestBody()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("OUTBOX_EVENT_NOT_FOUND"));
    }

    @Test
    void mapsOutboxEventNotFailedToConflictErrorEnvelope() throws Exception {
        when(outboxAdminService.requeue(anyString(), anyString(), anyString(), any()))
            .thenThrow(new OutboxEventNotFailedException("outbox-1", OutboxEventStatus.PENDING));

        mockMvc.perform(post("/api/v1/admin/outbox/outbox-1:requeue")
                .principal(actor())
                .header("X-Correlation-Id", "corr-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requeueRequestBody()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("OUTBOX_EVENT_NOT_FAILED"));
    }

    private static String requeueRequestBody() {
        return """
            {
              "reason": "root cause fixed"
            }
            """;
    }

    private static OutboxEventRecord outboxEventFixture() {
        return new OutboxEventRecord(
            "outbox-1", "ApprovalRequest", "ar-1", "approval.granted.v1", "v1", "{}", "corr-1", null,
            OutboxEventStatus.PENDING, 0, Instant.now(), null, Instant.now()
        );
    }

    private static LocalValidatorFactoryBean validator() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return validator;
    }
}
