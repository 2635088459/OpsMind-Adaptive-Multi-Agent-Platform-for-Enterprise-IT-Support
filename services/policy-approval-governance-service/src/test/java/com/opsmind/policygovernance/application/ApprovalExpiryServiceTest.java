package com.opsmind.policygovernance.application;

import com.opsmind.policygovernance.application.model.OutboxEventRecord;
import com.opsmind.policygovernance.application.port.ApprovalRequestRepository;
import com.opsmind.policygovernance.domain.approval.ApprovalExpiredEvent;
import com.opsmind.policygovernance.domain.approval.ApprovalRequest;
import com.opsmind.policygovernance.domain.approval.ApprovalStatus;
import com.opsmind.policygovernance.domain.approval.ApprovalType;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import com.opsmind.policygovernance.infrastructure.audit.SimpleAuditIntegrityAdapter;
import com.opsmind.policygovernance.support.FakeMessageBrokerPublisher;
import com.opsmind.policygovernance.support.InMemoryApprovalRequestRepository;
import com.opsmind.policygovernance.support.InMemoryGovernanceAuditRepository;
import com.opsmind.policygovernance.support.InMemoryOutboxEventRepository;
import com.opsmind.policygovernance.support.NoOpGovernanceMetrics;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class ApprovalExpiryServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final InMemoryApprovalRequestRepository requestRepository = new InMemoryApprovalRequestRepository();
    private final InMemoryOutboxEventRepository outboxEventRepository = new InMemoryOutboxEventRepository();
    private final GovernanceAuditService auditService = new GovernanceAuditService(
        new InMemoryGovernanceAuditRepository(), new SimpleAuditIntegrityAdapter(),
        new OutboxDispatchService(outboxEventRepository, new FakeMessageBrokerPublisher(), clock), clock
    );
    private final ApprovalExpiryService service = new ApprovalExpiryService(requestRepository, auditService, new NoOpGovernanceMetrics(), clock);

    @Test
    void expiresOnlyRequestedApprovalsPastTheirExpiry() {
        ApprovalRequest expired = ApprovalRequest.requested(
            "ar-1", "rk-1", "hash-1", "tool-gateway", "src-req-1", null, null, "tool-req-1", null, null,
            "requester-1", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH, List.of(), clock.instant().minusSeconds(10), clock.instant()
        );
        ApprovalRequest stillOpen = ApprovalRequest.requested(
            "ar-2", "rk-2", "hash-2", "tool-gateway", "src-req-2", null, null, "tool-req-2", null, null,
            "requester-1", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH, List.of(), clock.instant().plusSeconds(3600), clock.instant()
        );
        requestRepository.save(expired);
        requestRepository.save(stillOpen);

        List<ApprovalRequest> result = service.expireDue();

        assertThat(result).extracting(ApprovalRequest::approvalRequestId).containsExactly("ar-1");
        assertThat(requestRepository.findById("ar-1").orElseThrow().status()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(requestRepository.findById("ar-2").orElseThrow().status()).isEqualTo(ApprovalStatus.REQUESTED);
    }

    /**
     * SPEC-PG-012: {@code expireDue()} must stage the real {@code
     * approval.expired.v1} event, not the generic {@code
     * governance.audit.approval_expired.v1} placeholder — mirroring {@code
     * ApprovalServiceTest#requestStagesTheRealApprovalRequestedEventWithCorrectAggregateIdentity}.
     */
    @Test
    void expireDueStagesTheRealApprovalExpiredEventWithCorrectAggregateIdentity() {
        ApprovalRequest expired = ApprovalRequest.requested(
            "ar-3", "rk-3", "hash-3", "tool-gateway", "src-req-3", null, null, "tool-req-3", null, null,
            "requester-1", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH, List.of(), clock.instant().minusSeconds(10), clock.instant()
        );
        requestRepository.save(expired);

        service.expireDue();

        OutboxEventRecord staged = outboxEventRepository.all().stream()
            .filter(r -> r.eventType().equals(ApprovalExpiredEvent.EVENT_TYPE))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no approval.expired.v1 row was staged"));
        assertThat(staged.aggregateType()).isEqualTo("ApprovalRequest");
        assertThat(staged.aggregateId()).isEqualTo("ar-3");
        assertThat(staged.payloadJson()).contains("\"approvalRequestId\":\"ar-3\"");
    }

    /**
     * SPEC-PG-012 (10-failure-handling): one row failing to save (e.g. a
     * concurrent grant/deny raced this scan) must not stop the rest of the
     * batch from expiring.
     */
    @Test
    void aFailureExpiringOneRequestDoesNotStopTheRestOfTheBatch() {
        ApprovalRequest poison = ApprovalRequest.requested(
            "ar-poison", "rk-poison", "hash-p", "tool-gateway", "src-req-poison", null, null, "tool-req-p", null, null,
            "requester-1", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH, List.of(), clock.instant().minusSeconds(10), clock.instant()
        );
        ApprovalRequest healthy = ApprovalRequest.requested(
            "ar-healthy", "rk-healthy", "hash-h", "tool-gateway", "src-req-healthy", null, null, "tool-req-h", null, null,
            "requester-1", ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH, List.of(), clock.instant().minusSeconds(10), clock.instant()
        );
        ThrowingOnSaveRepository throwingRepository = new ThrowingOnSaveRepository(requestRepository, "ar-poison");
        throwingRepository.save(poison);
        throwingRepository.save(healthy);
        ApprovalExpiryService isolatedService = new ApprovalExpiryService(throwingRepository, auditService, new NoOpGovernanceMetrics(), clock);

        List<ApprovalRequest> result = isolatedService.expireDue();

        assertThat(result).extracting(ApprovalRequest::approvalRequestId).containsExactly("ar-healthy");
        assertThat(requestRepository.findById("ar-poison").orElseThrow().status()).isEqualTo(ApprovalStatus.REQUESTED);
        assertThat(requestRepository.findById("ar-healthy").orElseThrow().status()).isEqualTo(ApprovalStatus.EXPIRED);
    }

    /** Wraps the real in-memory double, failing {@link #save} only for one chosen id, to prove per-request failure isolation. */
    private static final class ThrowingOnSaveRepository implements ApprovalRequestRepository {

        private final ApprovalRequestRepository delegate;
        private final String poisonId;

        private ThrowingOnSaveRepository(ApprovalRequestRepository delegate, String poisonId) {
            this.delegate = delegate;
            this.poisonId = poisonId;
        }

        @Override
        public ApprovalRequest save(ApprovalRequest approvalRequest) {
            if (approvalRequest.approvalRequestId().equals(poisonId) && approvalRequest.status() == ApprovalStatus.EXPIRED) {
                throw new IllegalStateException("simulated save failure for " + poisonId);
            }
            return delegate.save(approvalRequest);
        }

        @Override
        public Optional<ApprovalRequest> findById(String approvalRequestId) {
            return delegate.findById(approvalRequestId);
        }

        @Override
        public Optional<ApprovalRequest> findByIdForUpdate(String approvalRequestId) {
            return delegate.findByIdForUpdate(approvalRequestId);
        }

        @Override
        public Optional<ApprovalRequest> findByRequestKey(String requestKey) {
            return delegate.findByRequestKey(requestKey);
        }

        @Override
        public List<ApprovalRequest> findRequestedExpiringBefore(Instant threshold) {
            return delegate.findRequestedExpiringBefore(threshold);
        }
    }
}
