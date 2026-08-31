"""SPEC-EI-030 (ticket-runtime-evaluation-contract) / SPEC-EI-031 (memory-tool-
evidence-contract) / SPEC-EI-032 (policy-approval-release-approval-contract) built the
real consumer this package's own docstring once described as future work: `router.py`
now serves eight event-ingestion endpoints under `/internal/evaluation/v1/events/*` —
ticket-resolved/ticket-reopened/workflow-completed/workflow-failed/tool-completed/
memory-retrieval-completed (funnel into `CollectOnlineSampleService`, closing
04-use-cases UC-EI-006's own consume step) and approval-granted/approval-denied
(drive `CreateImprovementCandidateUseCase.approve()`/`reject()`, closing the
SPEC-EI-026 request/consume loop). See `application.services.
consume_cross_domain_event`/`consume_approval_decision_event`'s own module
docstrings for the redaction and dedup rules; `infrastructure.messaging.
rabbitmq_consumer`'s own module docstring still marks where a real broker consumer
would eventually replace this REST stand-in, the same "manual/ops trigger" precedent
memory-knowledge-service's own interfaces/event/router.py already established.
"""
