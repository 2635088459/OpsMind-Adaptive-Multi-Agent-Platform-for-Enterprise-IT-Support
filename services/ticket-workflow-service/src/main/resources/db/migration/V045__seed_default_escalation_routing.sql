-- Real gap found live 2026-09-03, this platform's first real end-to-end
-- escalation attempt (SPEC-ARO-041, driven through the real employee-portal
-- UI): agent-runtime-service's own Settings.escalation_default_category_id/
-- escalation_default_support_queue_id default to "" and
-- EscalationRoutingNotConfiguredException fails the message turn with a
-- real 502 rather than fabricate an id that may not resolve. This is a
-- previously-documented, real deployment gap (settings.py's own comment:
-- "no seed data for categoryId/supportQueueId exists anywhere in this
-- platform"), not new.
--
-- A real category/support-queue row for these exact ids (11111111-.../
-- 33333333-...) already exists in this local Postgres volume — confirmed
-- live by querying it directly — but was never captured by any Flyway
-- migration (no file in this directory references either id), so a truly
-- fresh environment (a new volume, or a fresh CI database) would not have
-- it and would hit the exact same 502 this platform just hit. `ON CONFLICT
-- DO NOTHING` makes this migration a no-op against the already-seeded local
-- volume while still making the data real and reproducible everywhere else.
--
-- `team_id` reuses "network-support-team", the SAME real `support_teams`
-- claim opsmind-realm.json's own integration-test-client/support-console
-- clients already carry (confirmed by reading that realm file directly) —
-- inventing a different team_id here that no real identity in this realm is
-- ever authorized against would create a ticket no one could ever actually
-- act on through SupportQueueScope's own real authorization check.
INSERT INTO ticket.ticket_categories (category_id, code, display_name, active, created_at, updated_at)
VALUES ('11111111-1111-1111-1111-111111111111', 'NETWORK', 'Network', TRUE, now(), now())
ON CONFLICT (category_id) DO NOTHING;

INSERT INTO ticket.support_queues (support_queue_id, team_id, display_name, active, created_at, updated_at)
VALUES ('33333333-3333-3333-3333-333333333333', 'network-support-team', 'Network Support Queue', TRUE, now(), now())
ON CONFLICT (support_queue_id) DO NOTHING;
