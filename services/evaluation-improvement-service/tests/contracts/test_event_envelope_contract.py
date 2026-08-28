"""06-event-contracts §"Event Envelope 要求": every published event must carry
eventId/eventType/eventVersion/occurredAt/producer/traceId/correlationId/runId/
candidateId/payload. Checked against the real OutboxRecord.payload
DispatchOutboxEventsService actually publishes — a schema drift here breaks CI per
14-testing-strategy §"Contract Tests": "invalid payload ... 必须失败."
"""

from __future__ import annotations

import json

import pytest

from evaluationimprovement.application.commands import (
    AddTestCasesCommand,
    CreateDatasetCommand,
    CreateRunCommand,
    PublishDatasetCommand,
    SubmitDatasetForReviewCommand,
    TestCaseInput,
)
from evaluationimprovement.container import Container
from evaluationimprovement.domain.enums import Criticality

_REQUIRED_ENVELOPE_FIELDS = {
    "eventId", "eventType", "eventVersion", "occurredAt", "producer", "traceId", "correlationId", "runId", "candidateId",
    "payload",
}


@pytest.mark.unit
def test_run_requested_envelope_has_every_required_field(container: Container) -> None:
    dataset = container.create_dataset_service.create_dataset(CreateDatasetCommand(
        name="identity-mfa-golden", version="2026.08.1", domain="IDENTITY_ACCESS", scenario_tags=(), created_by="author-1",
        actor="author-1", correlation_id="corr-1",
    ))
    case = TestCaseInput(
        case_key="k1", scenario="s", user_request_redacted="", mock_system_state={}, ground_truth={"classification": "X"},
        allowed_tools=(), forbidden_tools=(), required_approval=False, verification_condition={}, criticality=Criticality.STANDARD,
    )
    container.create_dataset_service.add_test_cases(AddTestCasesCommand(dataset_id=dataset.dataset_id, cases=(case,), actor="author-1", correlation_id="corr-1"))
    container.publish_dataset_service.submit_for_review(SubmitDatasetForReviewCommand(dataset_id=dataset.dataset_id, actor="author-1", correlation_id="corr-1"))

    published = container.publish_dataset_service.publish(PublishDatasetCommand(dataset_id=dataset.dataset_id, published_by="reviewer-1", actor="reviewer-1", correlation_id="corr-1"))
    run = container.create_run_service.create_run(CreateRunCommand(
        run_key="contract-001", dataset_id=published.dataset_id, target_version="agent-runtime:rc1", baseline_version=None,
        grader_bundle_version="v1", policy_version="v1", gate_policy="mvp-release-gate-v1", triggered_by="ci",
        actor="ci", correlation_id="corr-1",
    ))

    records = container.outbox_repository.find_dispatchable(container.clock.now(), 10)
    matching = [r for r in records if r.event_type == "evaluation.run.requested.v1"]
    assert len(matching) == 1

    envelope = json.loads(matching[0].payload)
    assert _REQUIRED_ENVELOPE_FIELDS <= envelope.keys()
    assert envelope["eventType"] == "evaluation.run.requested.v1"
    assert envelope["runId"] == str(run.run_id.value)
    assert envelope["producer"] == "evaluation-improvement-service"
