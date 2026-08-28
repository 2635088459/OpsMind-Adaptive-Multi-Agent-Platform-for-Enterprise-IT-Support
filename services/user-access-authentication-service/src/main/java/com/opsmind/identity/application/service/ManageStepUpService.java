package com.opsmind.identity.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsmind.identity.application.command.CancelStepUpChallengeCommand;
import com.opsmind.identity.application.command.ConsumeStepUpChallengeCommand;
import com.opsmind.identity.application.command.RequestStepUpChallengeCommand;
import com.opsmind.identity.application.command.VerifyStepUpChallengeCommand;
import com.opsmind.identity.application.exception.StepUpBindingMismatchException;
import com.opsmind.identity.application.exception.StepUpChallengeNotFoundException;
import com.opsmind.identity.application.exception.IdpUnavailableException;
import com.opsmind.identity.application.exception.StepUpEvidenceRejectedException;
import com.opsmind.identity.application.exception.UserSessionNotFoundException;
import com.opsmind.identity.application.port.in.ManageStepUpUseCase;
import com.opsmind.identity.application.port.out.AuditPort;
import com.opsmind.identity.application.port.out.ClockPort;
import com.opsmind.identity.application.port.out.EventPublisherPort;
import com.opsmind.identity.application.port.out.HashingPort;
import com.opsmind.identity.application.port.out.IdentityMetricsPort;
import com.opsmind.identity.application.port.out.OidcProviderPort;
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
import com.opsmind.identity.domain.user.ExternalSubject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * UC-UA-006: request/verify/consume/cancel a {@link StepUpChallenge} and
 * reconcile its timeout (02-business-invariants #10/#11; 03-state-machine
 * §StepUpChallenge).
 *
 * <p>SPEC-UA-017 (Step Up Challenge Lifecycle) closes the lifecycle-mechanics
 * gaps 03-state-machine's own diagram named but this class left unwired:
 * {@link #cancel} (the {@code PENDING --cancel--> CANCELLED} edge — the
 * domain method existed since SPEC-UA-001 with zero real caller anywhere)
 * and the real "action/resource mismatch preserves state and writes a
 * denial audit" check on {@link #consume} (INV-UA-005; 04-use-cases
 * §Step-up: "Reject ... binding mismatch") — 05-api-contracts' own {@code
 * POST /step-up/proofs/{handle}/consume} row names exactly this shape:
 * "action/resource/correlation."
 *
 * <p>SPEC-UA-018 (Step Up Proof Verification) makes {@link #verify} check
 * real evidence for the first time: {@code issuer}/{@code subject} must
 * match the challenge's own bound {@link StepUpChallenge#externalSubject},
 * the presented nonce must hash to the challenge's own stored {@code
 * nonceHash} (proof this specific re-authentication belongs to this
 * specific challenge), and the achieved {@code acr}/{@code amr} must meet
 * the challenge's own {@code requiredAssuranceLevel}/{@code
 * requiredMethods} — the same exact-acr-match / must-contain-amr convention
 * SPEC-UA-016's own authorization-decision assurance check already
 * established. This evidence is produced by a real forced Keycloak
 * re-authentication (the browser flow SPEC-UA-018 also builds — see {@code
 * api.browser.StepUpVerificationSuccessHandler}); {@link #request}'s own
 * nonce is real, cryptographically random material generated for exactly
 * this purpose, not a throwaway placeholder.
 *
 * <p>09-concurrency-and-idempotency: {@link #consume} relies on {@link
 * StepUpChallengeRepository#tryConsume} — the real atomic conditional
 * update — so at most one caller ever succeeds, even under a race.
 */
@Service
public class ManageStepUpService implements ManageStepUpUseCase {

    private static final String AGGREGATE_TYPE = "StepUpChallenge";

    private final StepUpChallengeRepository challengeRepository;
    private final UserSessionRepository sessionRepository;
    private final AuditPort auditPort;
    private final EventPublisherPort eventPublisherPort;
    private final HashingPort hashingPort;
    private final IdentityMetricsPort identityMetricsPort;
    private final OidcProviderPort oidcProviderPort;
    private final ClockPort clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ManageStepUpService(
        StepUpChallengeRepository challengeRepository, UserSessionRepository sessionRepository, AuditPort auditPort,
        EventPublisherPort eventPublisherPort, HashingPort hashingPort, IdentityMetricsPort identityMetricsPort,
        OidcProviderPort oidcProviderPort, ClockPort clock
    ) {
        this.challengeRepository = challengeRepository;
        this.sessionRepository = sessionRepository;
        this.auditPort = auditPort;
        this.eventPublisherPort = eventPublisherPort;
        this.hashingPort = hashingPort;
        this.identityMetricsPort = identityMetricsPort;
        this.oidcProviderPort = oidcProviderPort;
        this.clock = clock;
    }

    /**
     * SPEC-UA-032 (10-failure-handling: "Keycloak unavailable ... new
     * login, step-up, and sensitive actions return 503/fail closed").
     * Checked right after the session is resolved (needed for the audit
     * write) but before a new challenge is ever created — a PENDING
     * challenge the user could never complete against a down IdP is worse
     * than no challenge at all.
     */
    @Override
    @Transactional
    public StepUpChallenge request(RequestStepUpChallengeCommand command) {
        UserSession session = sessionRepository.findById(command.userSessionId())
            .orElseThrow(() -> new UserSessionNotFoundException(command.userSessionId()));

        if (!oidcProviderPort.isAvailable()) {
            auditPort.record(IdentityAuditRecord.record(
                UUID.randomUUID().toString(), session.tenantId(), IdentityAuditAction.STEPUP_FAILED, null,
                session.externalSubject().subject(), null, AuditOutcome.DENIED, "IdP availability could not be confirmed",
                new CorrelationId(command.correlationId()), clock.now()
            ));
            identityMetricsPort.recordStepUpOutcome("REJECTED");
            throw new IdpUnavailableException("step-up challenge");
        }

        Instant now = clock.now();
        AuthorizationTarget target = new AuthorizationTarget(command.action(), command.resourceType(), command.resourceId());
        StepUpChallenge requested = StepUpChallenge.request(
            UUID.randomUUID().toString(), UUID.randomUUID().toString(), session.tenantId(), session.externalSubject(),
            session.userSessionId(), target, command.requiredAssuranceLevel(), command.requiredMethods(),
            command.maxAttempts(), command.correlationId(), now, now.plus(command.ttl())
        ).dispatch(command.nonceHash(), now);
        StepUpChallenge saved = challengeRepository.save(requested);
        audit(saved, IdentityAuditAction.STEPUP_REQUESTED, AuditOutcome.SUCCESS, "action=" + saved.target().action());
        identityMetricsPort.recordStepUpOutcome("REQUESTED");
        return saved;
    }

    /**
     * SPEC-UA-018: {@code issuer}/{@code subject}/nonce/assurance are all
     * checked against real re-authentication evidence before the challenge
     * ever transitions — any failure ({@link IllegalStepUpTransitionException}
     * for a bad status/expiry, {@link StepUpEvidenceRejectedException} for
     * rejected evidence) counts as a failed attempt the exact same way.
     */
    @Override
    @Transactional
    public StepUpChallenge verify(VerifyStepUpChallengeCommand command) {
        StepUpChallenge challenge = findByIdOrThrow(command.stepUpChallengeId());
        try {
            checkEvidence(challenge, command);
            String proofIdHash = hashingPort.hash(
                command.acr() + "|" + String.join(",", command.amr() == null ? List.of() : command.amr()) + "|" + clock.now()
            );
            StepUpChallenge verified = challenge.verify(proofIdHash, clock.now());
            StepUpChallenge saved = challengeRepository.save(verified);
            audit(saved, IdentityAuditAction.STEPUP_VERIFIED, AuditOutcome.SUCCESS, "action=" + saved.target().action());
            publish(saved, "identity.assurance.verified.v1");
            identityMetricsPort.recordStepUpOutcome("VERIFIED");
            return saved;
        } catch (IllegalStepUpTransitionException | StepUpEvidenceRejectedException failure) {
            // SPEC-UA-023: this catch block re-throws `failure` below, so a plain save() here would share
            // verify()'s own transactional boundary and be silently rolled back with everything else —
            // saveIsolated() commits independently (see StepUpChallengeRepository#saveIsolated's own javadoc).
            // The metrics counter below is a plain in-memory MeterRegistry operation, never part of any
            // DB transaction, so it needs no such isolation to survive the re-throw.
            StepUpChallenge afterFailedAttempt = challenge.status() == StepUpStatus.PENDING
                ? challengeRepository.saveIsolated(challenge.failAttempt(clock.now()))
                : challenge;
            audit(afterFailedAttempt, IdentityAuditAction.STEPUP_FAILED, AuditOutcome.FAILED, failure.getMessage());
            identityMetricsPort.recordStepUpOutcome("REJECTED");
            throw failure;
        }
    }

    /**
     * INV-UA-005 ("Step-up evidence binds issuer, subject, session, action,
     * resource, assurance, and expiry"): the issuer/subject and assurance
     * legs of that binding, checked against the caller's own real
     * re-authentication evidence rather than trusted blindly.
     */
    private void checkEvidence(StepUpChallenge challenge, VerifyStepUpChallengeCommand command) {
        ExternalSubject reauthenticated = new ExternalSubject(command.issuer(), command.subject());
        if (!challenge.externalSubject().equals(reauthenticated)) {
            throw new StepUpEvidenceRejectedException(challenge.stepUpChallengeId(), "re-authenticated subject does not match the challenge's own bound subject");
        }
        String presentedNonceHash = command.rawNonce() == null ? null : hashingPort.hash(command.rawNonce());
        if (presentedNonceHash == null || !presentedNonceHash.equals(challenge.nonceHash())) {
            throw new StepUpEvidenceRejectedException(challenge.stepUpChallengeId(), "nonce does not match this specific challenge");
        }
        boolean levelSatisfied = challenge.requiredAssuranceLevel() == null || challenge.requiredAssuranceLevel().equals(command.acr());
        List<String> achievedMethods = command.amr() == null ? List.of() : command.amr();
        boolean methodsSatisfied = challenge.requiredMethods().isEmpty() || achievedMethods.containsAll(challenge.requiredMethods());
        if (!levelSatisfied || !methodsSatisfied) {
            throw new StepUpEvidenceRejectedException(challenge.stepUpChallengeId(), "achieved assurance does not meet what the challenge requires");
        }
    }

    /**
     * 09-concurrency-and-idempotency: single-use — legal only from {@code
     * VERIFIED}, enforced by the real DB atomic conditional update.
     *
     * <p>03-state-machine §StepUpChallenge: "Action/resource mismatch
     * preserves state and writes a denial audit" (INV-UA-005; 04-use-cases
     * §Step-up: "Reject ... binding mismatch"). Checked BEFORE attempting
     * the atomic consume, so a mismatch never transitions or attempt-counts
     * the challenge at all — it is left exactly as it was.
     */
    @Override
    @Transactional
    public StepUpChallenge consume(ConsumeStepUpChallengeCommand command) {
        StepUpChallenge challenge = findByIdOrThrow(command.stepUpChallengeId());
        AuthorizationTarget asserted = new AuthorizationTarget(command.action(), command.resourceType(), command.resourceId());
        if (!challenge.target().equals(asserted)) {
            audit(challenge, IdentityAuditAction.STEPUP_FAILED, AuditOutcome.DENIED, "action/resource does not match the challenge's own bound target");
            throw new StepUpBindingMismatchException(challenge.stepUpChallengeId());
        }

        Instant now = clock.now();
        boolean consumed = challengeRepository.tryConsume(challenge.stepUpChallengeId(), now);
        if (!consumed) {
            audit(challenge, IdentityAuditAction.STEPUP_FAILED, AuditOutcome.DENIED, "current status was " + challenge.status());
            throw new IllegalStepUpTransitionException(challenge.status(), StepUpStatus.CONSUMED);
        }
        StepUpChallenge saved = challenge.consume(now);
        audit(saved, IdentityAuditAction.STEPUP_CONSUMED, AuditOutcome.SUCCESS, "action=" + saved.target().action());
        identityMetricsPort.recordStepUpOutcome("CONSUMED");
        return saved;
    }

    /** 03-state-machine §StepUpChallenge: {@code PENDING --cancel--> CANCELLED} — withdraws a challenge before it is ever verified. */
    @Override
    @Transactional
    public StepUpChallenge cancel(CancelStepUpChallengeCommand command) {
        StepUpChallenge challenge = findByIdOrThrow(command.stepUpChallengeId());
        StepUpChallenge saved = challengeRepository.save(challenge.cancel(clock.now()));
        audit(saved, IdentityAuditAction.STEPUP_CANCELLED, AuditOutcome.SUCCESS, "cancelled before verification");
        identityMetricsPort.recordStepUpOutcome("CANCELLED");
        return saved;
    }

    @Override
    public StepUpChallenge findById(String stepUpChallengeId) {
        return findByIdOrThrow(stepUpChallengeId);
    }

    /** 03-state-machine §StepUpChallenge: {@code PENDING --timeout--> EXPIRED} — admin/scheduler-triggered. */
    @Override
    @Transactional
    public int reconcileExpired() {
        Instant now = clock.now();
        int count = 0;
        for (StepUpChallenge pending : challengeRepository.findPendingExpired(now)) {
            StepUpChallenge saved = challengeRepository.save(pending.expire(now));
            audit(saved, IdentityAuditAction.STEPUP_EXPIRED, AuditOutcome.SUCCESS, "timeout reached");
            identityMetricsPort.recordStepUpOutcome("EXPIRED");
            count++;
        }
        return count;
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

    private void publish(StepUpChallenge challenge, String eventType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("challengeId", challenge.stepUpChallengeId());
        payload.put("subjectRef", challenge.externalSubject().subject());
        payload.put("assuranceLevel", challenge.requiredAssuranceLevel());
        payload.put("expiresAt", challenge.expiresAt().toString());
        try {
            eventPublisherPort.publish(eventType, AGGREGATE_TYPE, challenge.stepUpChallengeId(), objectMapper.writeValueAsString(payload), challenge.correlationId());
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize step-up event payload", e);
        }
    }
}
