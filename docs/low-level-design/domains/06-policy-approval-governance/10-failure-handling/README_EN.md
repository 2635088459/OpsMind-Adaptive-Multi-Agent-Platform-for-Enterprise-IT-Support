# 10 Failure Handling

## Policy Evaluation Failure

If rule parsing fails, policy version is missing, or evaluator crashes:

- return `EVALUATION_FAILED`;
- write audit;
- do not default allow;
- high-risk entry points should fail closed.

## Approval Timeout

When approval passes `expiresAt`:

- Expiry worker moves it to `EXPIRED`;
- publishes `approval.expired.v1`;
- downstream decides retry, human intervention, or cancellation.

## Poison Decision

The following enter poison handling:

- same request repeatedly crashes evaluator;
- approval payload does not match source linkage;
- outbox publish repeatedly fails;
- policy rule is incompatible with schema.

## Degraded Policy Mode

When policy evaluator is unavailable:

- high-risk mutation fails closed;
- low-risk read-only may use latest published policy cache;
- audit/metric must mark `degraded=true`;
- decisions without policy version are not allowed.

## Recovery

On service startup:

1. replay pending outbox;
2. scan expired approvals;
3. check policy version consistency;
4. reschedule poison review;
5. restore evaluator cache.

