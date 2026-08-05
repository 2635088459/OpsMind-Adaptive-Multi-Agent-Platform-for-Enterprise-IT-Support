# SPEC-TW-019 — Domain Rules

| Current | Target | Transition ID | Reason Code |
|---|---|---|---|
| `EXECUTING` | `VERIFYING` | `SM-021` | `TOOL_EXECUTION_COMPLETED` |

Invariants:

- event comes from trusted Tool Gateway;
- `toolExecutionId` matches the current execution attempt;
- `workflowId`, `actionId`, and `authorizationReference` match;
- successful result only starts verification;
- result payload does not contain secrets or full tool logs.
