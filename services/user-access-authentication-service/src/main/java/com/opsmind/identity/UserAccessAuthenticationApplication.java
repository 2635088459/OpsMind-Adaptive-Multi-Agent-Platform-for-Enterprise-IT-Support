package com.opsmind.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the User Access And Authentication service (domain 01).
 *
 * <p>Per {@code docs/low-level-design/domains/01-user-access-authentication/02-business-invariants}
 * this service is the identity and authorization security boundary: external
 * Keycloak is the sole source of truth for credentials, OIDC/OAuth2, and MFA;
 * this service owns trusted identity mapping, authorization context,
 * session/revocation metadata, step-up evidence, and audit — never passwords,
 * MFA secrets, raw tokens, or IdP private keys (INV-UA-001) — and denies by
 * default (INV-UA-002). See the {@code architecture} test package for the
 * ArchUnit-enforced package boundaries this class's own module sits behind.
 */
@SpringBootApplication
public class UserAccessAuthenticationApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserAccessAuthenticationApplication.class, args);
    }
}
