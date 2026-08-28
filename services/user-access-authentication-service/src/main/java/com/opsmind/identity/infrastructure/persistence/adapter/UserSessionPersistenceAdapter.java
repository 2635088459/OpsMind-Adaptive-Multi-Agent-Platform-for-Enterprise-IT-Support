package com.opsmind.identity.infrastructure.persistence.adapter;

import com.opsmind.identity.application.port.out.UserSessionRepository;
import com.opsmind.identity.domain.session.UserSession;
import com.opsmind.identity.infrastructure.persistence.jpa.repository.SpringDataUserSessionJpaRepository;
import com.opsmind.identity.infrastructure.persistence.mapper.UserSessionMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** SPEC-UA-002. Replaces the SPEC-UA-001-scoped {@code InMemoryUserSessionRepository}. */
@Component
public class UserSessionPersistenceAdapter implements UserSessionRepository {

    private final SpringDataUserSessionJpaRepository repository;

    public UserSessionPersistenceAdapter(SpringDataUserSessionJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<UserSession> findById(String userSessionId) {
        return repository.findById(userSessionId).map(UserSessionMapper::toDomain);
    }

    @Override
    public List<UserSession> findActiveExpired(Instant now) {
        return repository.findByStatusAndExpiresAtLessThanEqual("ACTIVE", now).stream().map(UserSessionMapper::toDomain).toList();
    }

    @Override
    public Optional<UserSession> findActiveByIdpSessionIdHash(String idpSessionIdHash) {
        return repository.findFirstByStatusAndIdpSessionIdHash("ACTIVE", idpSessionIdHash).map(UserSessionMapper::toDomain);
    }

    @Override
    public List<UserSession> findRevokedPendingEndSessionNotification() {
        return repository.findByStatusAndEndSessionNotifiedAtIsNull("REVOKED").stream().map(UserSessionMapper::toDomain).toList();
    }

    @Override
    public List<UserSession> findAllActive() {
        return repository.findByStatus("ACTIVE").stream().map(UserSessionMapper::toDomain).toList();
    }

    @Override
    public boolean existsByTokenIdHash(String tokenIdHash) {
        return repository.existsByTokenIdHash(tokenIdHash);
    }

    @Override
    public UserSession save(UserSession session) {
        repository.save(UserSessionMapper.toEntity(session));
        return session;
    }
}
