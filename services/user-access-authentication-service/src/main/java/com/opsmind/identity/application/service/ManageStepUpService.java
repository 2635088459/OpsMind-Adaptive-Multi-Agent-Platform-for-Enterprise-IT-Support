package com.opsmind.identity.application.service;

import com.opsmind.identity.application.command.ConsumeStepUpChallengeCommand;
import com.opsmind.identity.application.command.RequestStepUpChallengeCommand;
import com.opsmind.identity.application.command.VerifyStepUpChallengeCommand;
import com.opsmind.identity.application.exception.StepUpChallengeNotFoundException;
import com.opsmind.identity.application.exception.UserSessionNotFoundException;
import com.opsmind.identity.application.port.in.ManageStepUpUseCase;
import com.opsmind.identity.application.port.out.AuditPort;
import com.opsmind.identity.application.port.out.ClockPort;
import com.opsmind.identity.application.port.out.StepUpChallengeRepository;
import com.opsmind.identity.application.port.out.UserSessionRepository;
import com.opsmind.identity.domain.audit.AuditOutcome;
import com.opsmind.identity.domain.audit.IdentityAuditAction;
import com.opsmind.identity.domain.audit.IdentityAuditRecord;
import com.opsmind.identity.domain.session.UserSession;
import com.opsmind.identity.domain.shared.CorrelationId;
import com.opsmind.identity.domain.stepup.AuthorizationTarget;
import com.opsmind.identity.domain.stepup.IllegalStepUpTransitionException;
import com.opsmind.identity.domain.stepup.StepUpChallenge;
import com.opsmind.identity.domain.stepup.StepUpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * UC-UA-006: request/verify/consume a {@link StepUpChallenge}
 * (02-business-invariants #10/#11). No actual proof material is checked —
 * SPEC-UA-017/018 own the real Keycloak MFA integration. {@link #request}
 * skips the {@code REQUESTED -> PENDING} dispatch step (Keycloak MFA
 * dispatch is SPEC-UA-017's job) and starts the challenge directly at
 * {@code PENDING} so {@link #verify} is reachable today.
 */
@Service
public class ManageStepUpService implements ManageStepUpUseCase {

    private final StepUpChallengeRepository challengeRepository;
    private final UserSessionRepository sessionRepository;
    private final AuditPort auditPort;
    private final ClockPort clock;

    public ManageStepUpService(
        StepUpChallengeRepository challengeRepository, UserSessionRepository sessionRepository, AuditPort auditPort, ClockPort clock
    ) {
        this.challengeRepository = challengeRepository;
        this.sessionRepository = sessionRepository;
        this.auditPort = auditPort;
        this.clock = clock;
    }

    @Override
    public StepUpChallenge request(RequestStepUpChallengeCommand command) {
        UserSession session = sessionRepository.findById(command.userSessionId())
            .orElseThrow(() -> new UserSessionNotFoundException(command.userSessionId()));

        Instant now = clock.now();
        AuthorizationTarget target = new AuthorizationTarget(command.action(), command.resourceType(), command.resourceId());
        StepUpChallenge requested = StepUpChallenge.request(
            UUID.randomUUID().toString(), UUID.randomUUID().toString(), session.tenantId(), session.externalSubject(),
            session.userSessionId(), target, command.requiredAssuranceLevel(), command.requiredMethods(),
            command.maxAttempts(), command.correlationId(), now, now.plus(command.ttl())
        ).dispatch(UUID.randomUUID().toString(), now);
        StepUpChallenge saved = challengeRepository.save(requested);
        audit(saved, IdentityAuditAction.STEPUP_REQUESTED, AuditOutcome.SUCCESS, "action=" + saved.target().action());
        return saved;
    }

    @Override
    public StepUpChallenge verify(VerifyStepUpChallengeCommand command) {
        StepUpChallenge challenge = findByIdOrThrow(command.stepUpChallengeId());
        try {
            StepUpChallenge verified = challenge.verify(command.proofIdHash(), clock.now());
            StepUpChallenge saved = challengeRepository.save(verified);
            audit(saved, IdentityAuditAction.STEPUP_VERIFIED, AuditOutcome.SUCCESS, "action=" + saved.target().action());
            return saved;
        } catch (IllegalStepUpTransitionException failure) {
            StepUpChallenge afterFailedAttempt = challenge.status() == StepUpStatus.PENDING
                ? challengeRepository.save(challenge.failAttempt(clock.now()))
                : challenge;
            audit(afterFailedAttempt, IdentityAuditAction.STEPUP_FAILED, AuditOutcome.FAILED, "current status was " + challenge.status());
            throw failure;
        }
    }

    /** 09-concurrency-and-idempotency: single-use — legal only from {@code VERIFIED}. */
    @Override
    public StepUpChallenge consume(ConsumeStepUpChallengeCommand command) {
        StepUpChallenge challenge = findByIdOrThrow(command.stepUpChallengeId());
        StepUpChallenge saved = challengeRepository.save(challenge.consume(clock.now()));
        audit(saved, IdentityAuditAction.STEPUP_CONSUMED, AuditOutcome.SUCCESS, "action=" + saved.target().action());
        return saved;
    }

    @Override
    public StepUpChallenge findById(String stepUpChallengeId) {
        return findByIdOrThrow(stepUpChallengeId);
    }

    private StepUpChallenge findByIdOrThrow(String stepUpChallengeId) {
        return challengeRepository.findById(stepUpChallengeId)
            .orElseThrow(() -> new StepUpChallengeNotFoundException(stepUpChallengeId));
    }

    private void audit(StepUpChallenge challenge, IdentityAuditAction action, AuditOutcome outcome, String reason) {
        auditPort.record(IdentityAuditRecord.record(
            UUID.randomUUID().toString(), challenge.tenantId(), action, null, challenge.externalSubject().subject(),
            challenge.stepUpChallengeId(), outcome, reason, new CorrelationId(challenge.correlationId()), clock.now()
        ));
    }
}
