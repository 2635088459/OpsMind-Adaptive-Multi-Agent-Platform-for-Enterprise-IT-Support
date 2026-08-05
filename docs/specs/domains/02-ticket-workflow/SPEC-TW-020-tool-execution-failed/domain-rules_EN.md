# SPEC-TW-020 — Domain Rules

| Current | Target | Transition ID | Reason Code |
|---|---|---|---|
| `EXECUTING` | `IN_PROGRESS` | `SM-022` | `TOOL_EXECUTION_FAILED_SAFE` |
| `EXECUTING` | `FAILED` | `SM-023` | `TOOL_EXECUTION_PIPELINE_FAILED` |

Failure must be classified as `KNOWN_SAFE`, `RETRYABLE_SAFE`, or `PIPELINE_FAILED`. `UNKNOWN_SIDE_EFFECT` belongs to SPEC-TW-021.
