# SPEC-TW-013 — API Contract

```http
POST /api/v1/tickets/{ticketId}/user-input-requests/{requestId}/reply
```

Request:

```json
{
  "body": "The laptop is connected to VPN and I attached the screenshot of the enrollment error.",
  "attachmentIds": ["att-001"]
}
```

Response:

```json
{
  "ticketId": "6c2ad02e-c394-41fb-8e38-dfffd581a59d",
  "requestId": "3d912886-9652-4d88-8a64-1297b50f14c7",
  "messageId": "82fcd416-9138-42ce-b177-598a024c5c0f",
  "previousStatus": "WAITING_FOR_USER",
  "status": "IN_PROGRESS",
  "answeredAt": "2026-08-03T19:15:00Z",
  "resumeApplied": true,
  "version": 22
}
```

Success returns `200 OK` with `ETag`.
