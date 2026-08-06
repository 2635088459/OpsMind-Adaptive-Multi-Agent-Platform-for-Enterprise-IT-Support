# SPEC-TW-022 — API 契约

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

Response: `201 Created`，返回 `verificationId`、`attemptNumber`、`status = VERIFYING`、`version`。
