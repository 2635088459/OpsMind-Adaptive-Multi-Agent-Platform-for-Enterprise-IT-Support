package com.opsmind.identity.support;

import com.opsmind.identity.application.port.out.OidcProviderPort;
import com.opsmind.identity.domain.user.ExternalSubject;

import java.util.ArrayList;
import java.util.List;

/** Fast, dependency-free application-service unit-test double for {@link OidcProviderPort}. Real implementation is {@code KeycloakOidcProviderAdapter} (SPEC-UA-004/009). */
public class FakeOidcProviderPort implements OidcProviderPort {

    private final List<ExternalSubject> endSessionRequests = new ArrayList<>();
    private boolean available = true;
    private RuntimeException failWith;

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public void requestEndSession(ExternalSubject externalSubject) {
        endSessionRequests.add(externalSubject);
        if (failWith != null) {
            throw failWith;
        }
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public void failNextRequestsWith(RuntimeException exception) {
        this.failWith = exception;
    }

    public List<ExternalSubject> endSessionRequests() {
        return endSessionRequests;
    }
}
