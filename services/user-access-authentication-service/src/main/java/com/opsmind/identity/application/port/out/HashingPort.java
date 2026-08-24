package com.opsmind.identity.application.port.out;

/** 13-package-and-class-design §Output Ports. Used to hash session/device/token identifiers and to compute an {@code AuthorizationDecision} input hash — never to hash a password or MFA secret (INV-UA-001, out of this domain entirely). */
public interface HashingPort {

    String hash(String value);
}
