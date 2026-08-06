# SPEC-TW-025 — API 契约

```http
POST /internal/v1/tickets/{ticketId}/verified-resolution
```

Request:

```json
{
  "verificationEvidenceId": "ve-300",
  "resolutionCode": "FIXED",
  "resolutionSummary": "Verification confirmed the requester can sign in after MFA reset."
}
```

Response：`200 OK`，返回 `status = RESOLVED`、`resolvedAt`、`verificationEvidenceId`、`version`。
