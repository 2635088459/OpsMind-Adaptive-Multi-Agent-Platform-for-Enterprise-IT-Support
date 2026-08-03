# SPEC-TW-013 — Persistence Design

Real service migration should be named:

```text
services/ticket-workflow-service/src/main/resources/db/migration/V019__user_reply_and_resume.sql
```

Spec reference file: `V013__user_reply_and_resume.sql`.

## Updates

Successful reply must:

- insert `ticket_messages`;
- update `ticket_user_input_requests.request_status = ANSWERED`;
- set `answered_message_id` and `answered_at`;
- update ticket `status = IN_PROGRESS`;
- clear `waiting_for_requester_since`;
- increment version;
- write status history, timeline, audit, outbox, and idempotency.

Update guards on request status:

```sql
WHERE request_id = :request_id
  AND ticket_id = :ticket_id
  AND request_status = 'OPEN'
```
