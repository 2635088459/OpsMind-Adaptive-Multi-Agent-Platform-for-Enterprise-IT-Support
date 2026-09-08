-- Real gap found live 2026-09-08 (frontend integration verification, driven
-- through the real support-console UI paths): SPEC-SC-011 assign
-- (TicketAssignmentApplicationService#resolveEligibleAssignee) fails closed
-- with ASSIGNEE_NOT_FOUND / ASSIGNEE_NOT_IN_QUEUE unless the assignee exists
-- in ticket.support_agents (active, role IT_SUPPORT/IT_ADMIN/IT_MANAGER) AND
-- has a ticket.support_queue_memberships row for the ticket's own
-- support_queue_id. No file in this migration directory ever seeded either
-- table, so every real assign attempt in a fresh environment 400s regardless
-- of a correct request body.
--
-- Same reasoning/shape as V045 (seed_default_escalation_routing): the two
-- agent_ids are the real Keycloak `sub` values of the opsmind-realm.json
-- `support.agent` / `support.admin` users (confirmed by querying the running
-- realm directly), so a real logged-in support user can self-assign a ticket
-- routed to the default Network Support queue (support_queue_id
-- 33333333-3333-3333-3333-333333333333, seeded by V045) without any further
-- manual step. `ON CONFLICT DO NOTHING` keeps this a no-op against a volume
-- that already had these rows added by hand.

INSERT INTO ticket.support_agents (agent_id, display_name, role, active, created_at, updated_at)
VALUES
    ('e85c3314-64e6-48bb-b494-26d75fee189d', 'Support Agent', 'IT_SUPPORT', TRUE, now(), now()),
    ('bb474907-54cb-4080-a8f3-4d00ba83741d', 'Support Admin', 'IT_ADMIN', TRUE, now(), now())
ON CONFLICT (agent_id) DO NOTHING;

INSERT INTO ticket.support_queue_memberships (agent_id, support_queue_id, created_at)
VALUES
    ('e85c3314-64e6-48bb-b494-26d75fee189d', '33333333-3333-3333-3333-333333333333', now()),
    ('bb474907-54cb-4080-a8f3-4d00ba83741d', '33333333-3333-3333-3333-333333333333', now())
ON CONFLICT (agent_id, support_queue_id) DO NOTHING;
