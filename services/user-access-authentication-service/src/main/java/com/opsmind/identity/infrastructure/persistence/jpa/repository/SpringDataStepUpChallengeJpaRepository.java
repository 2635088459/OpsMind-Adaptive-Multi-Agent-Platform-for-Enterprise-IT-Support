package com.opsmind.identity.infrastructure.persistence.jpa.repository;

import com.opsmind.identity.infrastructure.persistence.jpa.entity.StepUpChallengeJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface SpringDataStepUpChallengeJpaRepository extends JpaRepository<StepUpChallengeJpaEntity, String> {

    List<StepUpChallengeJpaEntity> findByStatusAndExpiresAtLessThanEqual(String status, Instant now);

    /**
     * 09-concurrency-and-idempotency: the real atomic conditional update —
     * {@code WHERE status='VERIFIED' AND expires_at>now()} — so at most one
     * concurrent caller's update actually flips a row; JPA's own optimistic
     * {@code @Version} check on a plain {@code save} could not enforce the
     * "already expired" half of this on its own.
     */
    @Modifying
    @Query("""
        UPDATE StepUpChallengeJpaEntity c SET c.status = 'CONSUMED', c.consumedAt = :now, c.version = c.version + 1
        WHERE c.stepUpChallengeId = :id AND c.status = 'VERIFIED' AND c.expiresAt > :now
        """)
    int tryConsume(@Param("id") String stepUpChallengeId, @Param("now") Instant now);
}
