"""SPEC-TG-007 06-event-contracts: ``policy.rule.changed.v1`` — "refresh
capability risk, connector enablement, network allowlist, and approval
requirement cache. It must not retroactively change completed executions, but
it may affect requests that have not executed yet." Not one of the seven
``application/`` filenames 13-package-and-class-design literally lists — added
the same way ``application.register_connector``/``application.
consume_approval_decision`` extended that set for their own event/admin
surfaces.

``adapters.policy.policy_client.StaticPolicyAdapter`` is a pure, stateless
function of capability name — it holds no live rule cache to refresh, so this
consumer's own scope today is the baseline dedup/audit mechanism
09-concurrency-and-idempotency requires of every event consumer, not a real
rule-cache mutation. A future spec that gives PolicyPort real, mutable state
(a rules table, e.g.) is what would make this consumer's own "apply" step do
more than record the fact — see ``ports.policy_port`` module docstring for the
same "real integration is 06-policy-approval-governance's job" scope note.
"""

from __future__ import annotations

from tool_gateway.application.audit import AuditRecorder
from tool_gateway.application.commands import ConsumePolicyRuleChangedCommand
from tool_gateway.ports.storage_port import AuditRecordRepository, ClockPort, ProcessedEventRepository

_CONSUMER_NAME = "policy-rule-changed-consumer"


class ConsumePolicyRuleChangedService:
    def __init__(
        self, processed_event_repository: ProcessedEventRepository, audit_record_repository: AuditRecordRepository, clock: ClockPort,
    ) -> None:
        self._processed_event_repository = processed_event_repository
        self._clock = clock
        self._audit_recorder = AuditRecorder(audit_record_repository, clock)

    def consume_policy_rule_changed(self, command: ConsumePolicyRuleChangedCommand) -> bool:
        if self._processed_event_repository.is_processed(command.event_id, _CONSUMER_NAME):
            return False

        self._audit_recorder.record(
            action="policy_rule_changed_received", resource_type="POLICY_RULE", resource_id=command.rule_id,
            outcome="ACKNOWLEDGED", actor_id="policy-approval-governance", correlation_id=command.correlation_id,
        )
        self._processed_event_repository.mark_processed(command.event_id, _CONSUMER_NAME, self._clock.now())
        return True
