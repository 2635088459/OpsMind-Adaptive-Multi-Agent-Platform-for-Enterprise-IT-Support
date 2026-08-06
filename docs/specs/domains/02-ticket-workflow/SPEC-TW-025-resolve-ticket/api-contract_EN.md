# SPEC-TW-025 — API Contract

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

Response: `200 OK` with `status = RESOLVED`, `resolvedAt`, `verificationEvidenceId`, and `version`.
