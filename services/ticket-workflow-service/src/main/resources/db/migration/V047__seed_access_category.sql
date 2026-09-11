-- Real gap found live 2026-09-11 (support-console triage form, driven by the
-- real UI): V045 only ever seeded one category ("Network"), so an access
-- request like "they need AD access" had no honest category to pick —
-- forcing an operator into the "Enter a different ID..." escape hatch with a
-- made-up id that fails backend UUID validation.
--
-- category_id/support_queue_id are independent columns (ticket_categories
-- carries no team_id at all — see V013's own schema), so a new category can
-- route to the SAME already-real "Network Support Queue"
-- (33333333-3333-3333-3333-333333333333, V045) without inventing a new team
-- or support_queue_memberships row: network-support-team is still the only
-- team any real Keycloak identity in this realm is authorized against
-- (V045's own reasoning), so a brand-new team here would again be a queue no
-- one could ever act on.
INSERT INTO ticket.ticket_categories (category_id, code, display_name, active, created_at, updated_at)
VALUES ('22222222-2222-2222-2222-222222222222', 'ACCESS', 'Access', TRUE, now(), now())
ON CONFLICT (category_id) DO NOTHING;
