# SPEC-ARO-043 — Domain Rules

Goal: support `Service Identity for Outbound Calls`.

- This is infrastructure, not domain business logic — but the outbound HTTP client it supports must still fail closed, matching the same posture this project applies everywhere else authentication is involved.
- No caller of the outbound client (SPEC-ARO-038/040/041) is allowed to bypass it with its own ad-hoc token handling.
