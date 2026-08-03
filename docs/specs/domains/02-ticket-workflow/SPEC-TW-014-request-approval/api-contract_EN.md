# SPEC-TW-014 — API Contract

```http
POST /api/v1/tickets/{ticketId}/approval-requests
```

Request:

```json
{
  "workflowId": "wf-9000",
  "actionId": "act-100",
  "actionType": "RESET_MFA",
  "riskLevel": "HIGH",
  "riskContext": {
    "targetSystem": "identity",
    "requesterImpact": "account_access"
  },
  "reason": "MFA reset requires approval before execution."
}
```

Response: `201 Created` with `approvalRequestId`, `approvalId`, `status = WAITING_FOR_APPROVAL`, and `version`.
