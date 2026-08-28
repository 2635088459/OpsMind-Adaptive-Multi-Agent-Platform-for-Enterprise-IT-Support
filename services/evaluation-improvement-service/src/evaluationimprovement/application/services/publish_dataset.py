"""13-package-and-class-design §"应用层": PublishDatasetService, the sole
implementation of PublishDatasetUseCase. 04-use-cases UC-EI-001 step 4: "发布 dataset
version." SPEC-EI-004 adds the rest of the dataset lifecycle 03-state-machine names
beyond publish: PUBLISHED -> DEPRECATED -> ARCHIVED. SPEC-EI-007 adds the
dataset-level `content_hash` publish() itself now freezes.
"""

from __future__ import annotations

import hashlib

from evaluationimprovement.application.commands import (
    ArchiveDatasetCommand,
    DeprecateDatasetCommand,
    PublishDatasetCommand,
    RejectDatasetReviewCommand,
    SubmitDatasetForReviewCommand,
)
from evaluationimprovement.application.exceptions import DatasetNotFoundException
from evaluationimprovement.application.ports_out import AuditRecordRepository, ClockPort, DatasetRepository, TestCaseRepository
from evaluationimprovement.application.services.audit import AuditRecorder
from evaluationimprovement.application.services.create_dataset import dataset_to_view
from evaluationimprovement.application.views import DatasetView
from evaluationimprovement.domain.dataset import EvaluationDataset
from evaluationimprovement.domain.ids import DatasetId


class PublishDatasetService:
    def __init__(
        self, dataset_repository: DatasetRepository, test_case_repository: TestCaseRepository,
        audit_record_repository: AuditRecordRepository, clock: ClockPort,
    ) -> None:
        self._dataset_repository = dataset_repository
        self._test_case_repository = test_case_repository
        self._clock = clock
        self._audit_recorder = AuditRecorder(audit_record_repository, clock)

    def publish(self, command: PublishDatasetCommand) -> DatasetView:
        """domain.dataset.EvaluationDataset.publish() enforces: non-empty case_count
        (DatasetHasNoTestCasesException), publisher != creator
        (SelfReviewNotAllowedException — 14-testing-strategy §"Security Tests"), and a
        legal state transition (InvalidStateTransitionException) — the dataset must
        already be REVIEWING; call submit_for_review() first. SPEC-EI-006: an earlier
        version of this method silently auto-elevated a DRAFT dataset through
        REVIEWING in the same call, which made reject_review() pointless (a rejected,
        once-again-DRAFT dataset could just be re-published immediately without
        anyone addressing the rejection) — removed for that reason, not an oversight.
        SPEC-EI-007: `content_hash` is a SHA-256 over the sorted (by case_key) list of
        every one of this dataset's own test cases' own `input_hash` — deterministic
        and reproducible given the same content, order-independent of insertion
        order. Concurrency: the repository's own compare-and-swap on `dataset.status`
        covers a racing second publisher.
        """
        dataset = self._require_dataset(command.dataset_id, command.tenant_id)
        original_status = dataset.status
        content_hash = _compute_content_hash(self._test_case_repository.find_by_dataset(dataset.dataset_id))

        published = dataset.publish(command.published_by, self._clock.now(), content_hash=content_hash)
        saved = self._dataset_repository.save(published, expected_status=original_status)

        self._audit_recorder.record(
            action="publish_dataset", resource_type="EVALUATION_DATASET", resource_id=str(saved.dataset_id),
            actor=command.actor, outcome="SUCCESS", correlation_id=command.correlation_id,
        )
        return dataset_to_view(saved)

    def submit_for_review(self, command: SubmitDatasetForReviewCommand) -> DatasetView:
        """SPEC-EI-006 / 04-use-cases UC-EI-001 step 3: DRAFT -> REVIEWING, the
        required step before publish() will accept the dataset."""
        dataset = self._require_dataset(command.dataset_id, command.tenant_id)
        reviewing = dataset.start_review()
        saved = self._dataset_repository.save(reviewing, expected_status=dataset.status)
        self._audit_recorder.record(
            action="submit_dataset_for_review", resource_type="EVALUATION_DATASET", resource_id=str(saved.dataset_id),
            actor=command.actor, outcome="SUCCESS", correlation_id=command.correlation_id,
        )
        return dataset_to_view(saved)

    def reject_review(self, command: RejectDatasetReviewCommand) -> DatasetView:
        """SPEC-EI-006: sends a REVIEWING dataset back to its author. A rejected
        dataset must be resubmitted (submit_for_review()) before it can be published
        again — publish()'s own state-machine check already refuses a DRAFT dataset.
        """
        dataset = self._require_dataset(command.dataset_id, command.tenant_id)
        rejected = dataset.reject_review()
        saved = self._dataset_repository.save(rejected, expected_status=dataset.status)
        self._audit_recorder.record(
            action="reject_dataset_review", resource_type="EVALUATION_DATASET", resource_id=str(saved.dataset_id),
            actor=command.actor, outcome="SUCCESS", correlation_id=command.correlation_id,
            detail=f'{{"reason": {command.reason!r}}}',
        )
        return dataset_to_view(saved)

    def deprecate(self, command: DeprecateDatasetCommand) -> DatasetView:
        """11-security §"审计": dataset deprecate must be audited."""
        dataset = self._require_dataset(command.dataset_id, command.tenant_id)
        deprecated = dataset.deprecate()
        saved = self._dataset_repository.save(deprecated, expected_status=dataset.status)
        self._audit_recorder.record(
            action="deprecate_dataset", resource_type="EVALUATION_DATASET", resource_id=str(saved.dataset_id),
            actor=command.actor, outcome="SUCCESS", correlation_id=command.correlation_id,
        )
        return dataset_to_view(saved)

    def archive(self, command: ArchiveDatasetCommand) -> DatasetView:
        dataset = self._require_dataset(command.dataset_id, command.tenant_id)
        archived = dataset.archive()
        saved = self._dataset_repository.save(archived, expected_status=dataset.status)
        self._audit_recorder.record(
            action="archive_dataset", resource_type="EVALUATION_DATASET", resource_id=str(saved.dataset_id),
            actor=command.actor, outcome="SUCCESS", correlation_id=command.correlation_id,
        )
        return dataset_to_view(saved)

    def _require_dataset(self, dataset_id: DatasetId, tenant_id: str) -> EvaluationDataset:
        dataset = self._dataset_repository.find_by_id(dataset_id)
        if dataset is None or dataset.tenant_id != tenant_id:
            raise DatasetNotFoundException(dataset_id)
        return dataset


def _compute_content_hash(cases) -> str:  # noqa: ANN001
    """SPEC-EI-007: sorted by case_key (never by insertion/save order, which
    save_many() gives no guarantee over) so the same set of cases always produces the
    same hash regardless of how they were added.
    """
    ordered_hashes = sorted(c.input_hash for c in cases)
    return hashlib.sha256(",".join(ordered_hashes).encode("utf-8")).hexdigest()
