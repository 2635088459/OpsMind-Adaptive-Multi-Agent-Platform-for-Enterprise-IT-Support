package com.opsmind.identity.support;

import com.opsmind.identity.application.port.out.UserSessionRepository;
import com.opsmind.identity.domain.session.SessionStatus;
import com.opsmind.identity.domain.session.UserSession;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Fast, dependency-free application-service unit-test double for {@link UserSessionRepository}. Real persistence is {@code UserSessionPersistenceAdapter} (SPEC-UA-002). */
public class InMemoryUserSessionRepository implements UserSessionRepository {

    private final Map<String, UserSession> byId = new ConcurrentHashMap<>();

    @Override
    public Optional<UserSession> findById(String userSessionId) {
        return Optional.ofNullable(byId.get(userSessionId));
    }

    @Override
    public List<UserSession> findActiveExpired(Instant now) {
        return byId.values().stream()
            .filter(s -> s.status() == SessionStatus.ACTIVE && !now.isBefore(s.expiresAt()))
            .toList();
    }

    @Override
    public Optional<UserSession> findActiveByIdpSessionIdHash(String idpSessionIdHash) {
        return byId.values().stream()
            .filter(s -> s.status() == SessionStatus.ACTIVE && idpSessionIdHash.equals(s.idpSessionIdHash()))
            .findFirst();
    }

    @Override
    public List<UserSession> findRevokedPendingEndSessionNotification() {
        return byId.values().stream()
            .filter(s -> s.status() == SessionStatus.REVOKED && s.endSessionNotifiedAt() == null)
            .toList();
    }

    @Override
    public List<UserSession> findAllActive() {
        return byId.values().stream()
            .filter(s -> s.status() == SessionStatus.ACTIVE)
            .toList();
    }

    @Override
    public boolean existsByTokenIdHash(String tokenIdHash) {
        return byId.values().stream().anyMatch(s -> tokenIdHash.equals(s.tokenIdHash()));
    }

    @Override
    public UserSession save(UserSession session) {
        byId.put(session.userSessionId(), session);
        return session;
    }
}
