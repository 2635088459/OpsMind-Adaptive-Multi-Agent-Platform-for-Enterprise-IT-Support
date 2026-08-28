"""06-event-contracts lists nine consumed events (`agent.workflow.completed.v1`,
`agent.workflow.failed.v1`, `ticket.resolved.v1`, `ticket.reopened.v1`,
`tool.execution.completed.v1`, `tool.execution.failed.v1`, `approval.granted.v1`,
`approval.denied.v1`, `memory.retrieval.completed.v1`), but none of them backs a named
use case in SPEC-EI-001's own 13-package-and-class-design service list (only
create_dataset/publish_dataset/create_run/execute_case/score_run/compare_regression/
evaluate_release_gate/create_improvement_candidate/manage_canary/
dispatch_outbox_events). Consuming these to drive online-sample evaluation (04-use-cases
UC-EI-006) is phase-06 (SPEC-EI-028) and phase-07 cross-domain-contracts (SPEC-EI-030/
031) scope. This package intentionally has no router yet — see
application.ports_out.ProcessedEventRepository's own docstring for the dedup mechanism
already defined ahead of its first real consumer, mirroring
memory-knowledge-service's own SPEC-MK-001 precedent for ProcessedEventRepository.
"""
