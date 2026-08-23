-- SPEC-PG-005 (03-state-machine §Policy Decision State Machine):
-- EVALUATING -> EVALUATION_FAILED is a distinct terminal outcome from
-- EVALUATING -> DENIED. `effect` stays DENY either way (fail-safe default);
-- this column lets a caller distinguish "the policy legitimately denied
-- this" from "the evaluator could not run" without a fifth DecisionEffect
-- value (01-domain-model freezes that enum at exactly four values).
ALTER TABLE governance.policy_decisions ADD COLUMN evaluation_failed BOOLEAN NOT NULL DEFAULT FALSE;
