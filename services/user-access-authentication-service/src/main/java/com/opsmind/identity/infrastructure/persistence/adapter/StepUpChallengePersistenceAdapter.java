package com.opsmind.identity.infrastructure.persistence.adapter;

import com.opsmind.identity.application.port.out.StepUpChallengeRepository;
import com.opsmind.identity.domain.stepup.StepUpChallenge;
import com.opsmind.identity.infrastructure.persistence.jpa.repository.SpringDataStepUpChallengeJpaRepository;
import com.opsmind.identity.infrastructure.persistence.mapper.StepUpChallengeMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** SPEC-UA-002/SPEC-UA-017. Replaces the SPEC-UA-001-scoped {@code InMemoryStepUpChallengeRepository}. */
@Component
public class StepUpChallengePersistenceAdapter implements StepUpChallengeRepository {

    private final SpringDataStepUpChallengeJpaRepository repository;

    public StepUpChallengePersistenceAdapter(SpringDataStepUpChallengeJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<StepUpChallenge> findById(String stepUpChallengeId) {
        return repository.findById(stepUpChallengeId).map(StepUpChallengeMapper::toDomain);
    }

    @Override
    public List<StepUpChallenge> findPendingExpired(Instant now) {
        return repository.findByStatusAndExpiresAtLessThanEqual("PENDING", now).stream().map(StepUpChallengeMapper::toDomain).toList();
    }

    /** {@code @Modifying} custom queries need their own transactional boundary when invoked without one already open. */
    @Override
    @Transactional
    public boolean tryConsume(String stepUpChallengeId, Instant now) {
        return repository.tryConsume(stepUpChallengeId, now) > 0;
    }

    @Override
    public StepUpChallenge save(StepUpChallenge challenge) {
        repository.save(StepUpChallengeMapper.toEntity(challenge));
        return challenge;
    }

    /** A dedicated {@code REQUIRES_NEW} transaction — see the port's own javadoc. */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StepUpChallenge saveIsolated(StepUpChallenge challenge) {
        repository.save(StepUpChallengeMapper.toEntity(challenge));
        return challenge;
    }
}
