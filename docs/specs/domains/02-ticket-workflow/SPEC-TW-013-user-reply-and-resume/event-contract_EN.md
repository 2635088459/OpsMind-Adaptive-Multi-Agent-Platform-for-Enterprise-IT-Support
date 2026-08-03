# SPEC-TW-013 — Event Contract

## ticket.user-reply-received.v1

```json
{
  "requestId": "3d912886-9652-4d88-8a64-1297b50f14c7",
  "messageId": "82fcd416-9138-42ce-b177-598a024c5c0f",
  "repliedBy": "alice",
  "repliedAt": "2026-08-03T19:15:00Z"
}
```

## ticket.user-input-resumed.v1

```json
{
  "requestId": "3d912886-9652-4d88-8a64-1297b50f14c7",
  "messageId": "82fcd416-9138-42ce-b177-598a024c5c0f",
  "previousStatus": "WAITING_FOR_USER",
  "newStatus": "IN_PROGRESS",
  "resumeReason": "USER_REPLIED",
  "resumedAt": "2026-08-03T19:15:00Z"
}
```

Events must not contain full message body, secrets, email, raw claims, or attachment content.
