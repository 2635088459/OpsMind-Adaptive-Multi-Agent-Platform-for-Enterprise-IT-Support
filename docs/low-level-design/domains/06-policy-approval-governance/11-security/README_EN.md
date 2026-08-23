# 11 Security

## Permission Model

06 uses RBAC + ABAC:

- RBAC decides whether a user can approve, publish policy, or view audit.
- ABAC decides whether that principal may act for a specific ticket, tenant, resource, and risk level.

## Separation Of Duties

By default, forbid:

- requester approving their own request;
- tool execution worker approving the corresponding tool request;
- policy author publishing their own unreviewed policy;
- admin repair initiator approving the high-risk override directly.

## Approval Authenticity

Approval command must include:

- authenticated actor;
- session/device metadata;
- idempotency key;
- reason;
- optional MFA/step-up marker;
- correlation id.

## Override Guard

Override must:

- have explicit scope;
- have expiry;
- have independent approver;
- have high-priority audit;
- be revocable;
- not become a permanent policy replacement.

## Sensitive Data

Policy input may include sensitive context summaries, but must not persist raw secrets. Audit API returns metadata and hashes by default.

## Tamper-Resistant Audit

Audit record should include hash chain or append-only marker. Ordinary admins cannot delete audit records; they may only be archived by retention policy.

