# SPEC-TW-022 — API Contract

```http
POST /internal/v1/tickets/{ticketId}/verification/start
```

Request:

```json
{
  "toolResultId": "tool-result-900",
  "verificationType": "IDENTITY_LOGIN_CHECK",
  "reason": "Confirm the requester can sign in after MFA reset."
}
```

Response: `201 Created` with `verificationId`, `attemptNumber`, `status = VERIFYING`, and `version`.
