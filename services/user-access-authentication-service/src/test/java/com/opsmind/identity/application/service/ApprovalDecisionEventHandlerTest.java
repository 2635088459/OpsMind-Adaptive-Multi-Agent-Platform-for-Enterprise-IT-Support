package com.opsmind.identity.application.service;

import com.opsmind.identity.application.command.ReconcileApprovalOutcomeCommand;
import com.opsmind.identity.application.port.in.ManageBreakGlassUseCase;
import com.opsmind.identity.domain.breakglass.ApprovalOutcome;
import com.opsmind.identity.support.InMemoryProcessedEventRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

/** SPEC-UA-028: orchestration (dedup, then reconcile) without Spring or RabbitMQ. */
@Tag("unit")
class ApprovalDecisionEventHandlerTest {

    private final ConsumedEventDeduplicationService deduplicationService = new ConsumedEventDeduplicationService(new InMemoryProcessedEventRepository());
    private final ManageBreakGlassUseCase manageBreakGlassUseCase = Mockito.mock(ManageBreakGlassUseCase.class);
    private final ApprovalDecisionEventHandler handler = new ApprovalDecisionEventHandler(deduplicationService, manageBreakGlassUseCase);

    @Test
    void aNewEventIsReconciled() {
        handler.handle("evt-1", "approval.denied.v1", "approval-ref-1", ApprovalOutcome.DENIED, "corr-1");

        ArgumentCaptor<ReconcileApprovalOutcomeCommand> captor = ArgumentCaptor.forClass(ReconcileApprovalOutcomeCommand.class);
        verify(manageBreakGlassUseCase).reconcileApprovalOutcome(captor.capture());
        assertThat(captor.getValue().approvalRequestId()).isEqualTo("approval-ref-1");
        assertThat(captor.getValue().outcome()).isEqualTo(ApprovalOutcome.DENIED);
        assertThat(captor.getValue().correlationId()).isEqualTo("corr-1");
    }

    /** 06-event-contracts §Idempotency: a redelivered message (same eventId) is a silent no-op. */
    @Test
    void aRedeliveredEventIsNotReconciledTwice() {
        handler.handle("evt-2", "approval.denied.v1", "approval-ref-2", ApprovalOutcome.DENIED, "corr-1");
        handler.handle("evt-2", "approval.denied.v1", "approval-ref-2", ApprovalOutcome.DENIED, "corr-1");

        verify(manageBreakGlassUseCase, Mockito.times(1)).reconcileApprovalOutcome(any());
    }

    @Test
    void differentEventIdsForTheSameApprovalAreBothProcessed() {
        handler.handle("evt-3", "approval.denied.v1", "approval-ref-3", ApprovalOutcome.DENIED, "corr-1");
        handler.handle("evt-4", "approval.expired.v1", "approval-ref-3", ApprovalOutcome.EXPIRED, "corr-1");

        verify(manageBreakGlassUseCase, Mockito.times(2)).reconcileApprovalOutcome(any());
    }
}
