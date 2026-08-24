package com.opsmind.identity.infrastructure.persistence.adapter;

import com.opsmind.identity.application.port.out.UserSessionRepository;
import com.opsmind.identity.domain.session.UserSession;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** SPEC-UA-001-scoped placeholder — see {@link InMemoryUserIdentityRepository}'s own javadoc for the deferral this mirrors. */
@Repository
public class InMemoryUserSessionRepository implements UserSessionRepository {

    private final Map<String, UserSession> byId = new ConcurrentHashMap<>();

    @Override
    public Optional<UserSession> findById(String userSessionId) {
        return Optional.ofNullable(byId.get(userSessionId));
    }

    @Override
    public UserSession save(UserSession session) {
        byId.put(session.userSessionId(), session);
        return session;
    }
}
