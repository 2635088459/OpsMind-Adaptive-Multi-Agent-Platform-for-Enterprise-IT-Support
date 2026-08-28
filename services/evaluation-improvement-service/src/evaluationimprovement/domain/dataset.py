"""01-domain-model §"EvaluationDataset". 02-business-invariants INV-EI-005: "Dataset
发布后不可变；变更必须创建新 version，并保留 lineage." SPEC-EI-007 adds `content_hash`:
07-data-model §"Artifact 引用" names dataset-level artifact/hash tracking as this
service's own concern, distinct from each EvaluationTestCase's own `input_hash`
(SPEC-EI-005/07-data-model `evaluation_test_cases`) — `content_hash` is the
aggregate-level hash over every one of a dataset's own test-case input_hash values,
frozen at publish() time so a published dataset's exact content is independently
verifiable/reproducible without re-reading every test case. SPEC-EI-008 adds
`tenant_id`: 11-security §"身份与权限": "07 依赖 01 提供 actor、service identity、tenant
scope 和 role claims" — a caller-asserted tenant, the same "trusted caller asserts"
placeholder precedent `created_by`/`actor` already use ahead of real 01 JWT
integration. Defaults to `"default"` so every pre-existing single-tenant caller
(and test) keeps working unchanged; a real multi-tenant caller asserts its own.
"""

from __future__ import annotations

import dataclasses
from datetime import datetime

from evaluationimprovement.domain.enums import DatasetStatus
from evaluationimprovement.domain.exceptions import DatasetHasNoTestCasesException, SelfReviewNotAllowedException
from evaluationimprovement.domain.ids import DatasetId
from evaluationimprovement.domain.state_machine import StateMachine

_TRANSITIONS: dict[DatasetStatus, frozenset[DatasetStatus]] = {
    DatasetStatus.DRAFT: frozenset({DatasetStatus.REVIEWING}),
    DatasetStatus.REVIEWING: frozenset({DatasetStatus.PUBLISHED, DatasetStatus.DRAFT}),
    DatasetStatus.PUBLISHED: frozenset({DatasetStatus.DEPRECATED}),
    DatasetStatus.DEPRECATED: frozenset({DatasetStatus.ARCHIVED}),
    DatasetStatus.ARCHIVED: frozenset(),
}
_STATE_MACHINE: StateMachine[DatasetStatus] = StateMachine("EvaluationDataset", _TRANSITIONS)


@dataclasses.dataclass(frozen=True, slots=True)
class EvaluationDataset:
    """01-domain-model: "Dataset 发布后不可原地修改；新增 case 或改 ground truth 必须产生新
    version." Frozen — every transition method returns a new instance; the repository
    layer is responsible for the compare-and-swap this implies (mirrors
    memory-knowledge-service's own MemoryCandidateRepository.save(expected_status=...)
    convention).
    """

    dataset_id: DatasetId
    name: str
    version: str
    domain: str
    scenario_tags: tuple[str, ...]
    status: DatasetStatus
    case_count: int
    lineage_parent_id: DatasetId | None
    created_by: str
    created_at: datetime
    published_by: str | None = None
    published_at: datetime | None = None
    content_hash: str | None = None
    tenant_id: str = "default"

    @staticmethod
    def create(
        dataset_id: DatasetId, name: str, version: str, domain: str, scenario_tags: tuple[str, ...], created_by: str,
        now: datetime, lineage_parent_id: DatasetId | None = None, tenant_id: str = "default",
    ) -> "EvaluationDataset":
        if not name or not name.strip():
            raise ValueError("dataset name must not be blank")
        if not version or not version.strip():
            raise ValueError("dataset version must not be blank")
        if not created_by or not created_by.strip():
            raise ValueError("createdBy must not be blank")
        return EvaluationDataset(
            dataset_id=dataset_id, name=name, version=version, domain=domain, scenario_tags=scenario_tags,
            status=DatasetStatus.DRAFT, case_count=0, lineage_parent_id=lineage_parent_id, created_by=created_by,
            created_at=now, tenant_id=tenant_id,
        )

    def with_case_count(self, case_count: int) -> "EvaluationDataset":
        return dataclasses.replace(self, case_count=case_count)

    def start_review(self) -> "EvaluationDataset":
        _STATE_MACHINE.assert_transition(self.status, DatasetStatus.REVIEWING)
        return dataclasses.replace(self, status=DatasetStatus.REVIEWING)

    def reject_review(self) -> "EvaluationDataset":
        """SPEC-EI-006 / 03-state-machine: REVIEWING -> DRAFT — a reviewer sends the
        dataset back to its author instead of publishing it. `publish()` itself
        already refuses a stale/DRAFT dataset via the same state machine, so a
        rejected dataset cannot be accidentally published afterward without first
        being resubmitted (start_review()).
        """
        _STATE_MACHINE.assert_transition(self.status, DatasetStatus.DRAFT)
        return dataclasses.replace(self, status=DatasetStatus.DRAFT)

    def publish(self, published_by: str, now: datetime, content_hash: str | None = None) -> "EvaluationDataset":
        """02-business-invariants §"必须遵守" (SPEC-EI-001 domain-rules): a dataset
        cannot publish with zero cases, and cannot be self-published by its own
        creator (14-testing-strategy §"Security Tests"). SPEC-EI-007: `content_hash`
        is frozen here, at the moment the dataset becomes immutable — the application
        layer computes it (it needs the test cases themselves, which this aggregate
        does not hold); `None` stays a legal value for callers that don't compute one
        (e.g. every existing domain unit test), matching content_hash's own optional
        field default.
        """
        _STATE_MACHINE.assert_transition(self.status, DatasetStatus.PUBLISHED)
        if self.case_count <= 0:
            raise DatasetHasNoTestCasesException()
        if published_by == self.created_by:
            raise SelfReviewNotAllowedException()
        return dataclasses.replace(
            self, status=DatasetStatus.PUBLISHED, published_by=published_by, published_at=now, content_hash=content_hash,
        )

    def deprecate(self) -> "EvaluationDataset":
        _STATE_MACHINE.assert_transition(self.status, DatasetStatus.DEPRECATED)
        return dataclasses.replace(self, status=DatasetStatus.DEPRECATED)

    def archive(self) -> "EvaluationDataset":
        _STATE_MACHINE.assert_transition(self.status, DatasetStatus.ARCHIVED)
        return dataclasses.replace(self, status=DatasetStatus.ARCHIVED)

    @property
    def is_mutable(self) -> bool:
        """02-business-invariants INV-EI-005: only DRAFT/REVIEWING datasets accept new
        test cases; PUBLISHED and beyond are immutable.
        """
        return self.status in (DatasetStatus.DRAFT, DatasetStatus.REVIEWING)
