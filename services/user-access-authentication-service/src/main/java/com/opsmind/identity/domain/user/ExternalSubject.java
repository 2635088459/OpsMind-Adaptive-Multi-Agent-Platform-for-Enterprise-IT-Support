package com.opsmind.identity.domain.user;

/** 01-domain-model §Value Objects. The IdP's own `(issuer, subject)` — the stable identity key alongside tenant; never replaced by username/email. */
public record ExternalSubject(String issuer, String subject) {

    public ExternalSubject {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("issuer must not be blank");
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
    }
}
