"""13-package-and-class-design §"应用层": CreateDatasetService, the sole
implementation of CreateDatasetUseCase and DatasetQueryUseCase. 04-use-cases
UC-EI-001 steps 1-2: "Evaluator 创建 dataset draft" / "添加 test cases."
"""

from __future__ import annotations

from evaluationimprovement.application.commands import AddTestCasesCommand, CreateDatasetCommand, CreateDatasetVersionCommand
from evaluationimprovement.application.exceptions import DatasetNotFoundException, DatasetVersionConflictException, TestCaseNotFoundException
from evaluationimprovement.application.ports_out import AuditRecordRepository, ClockPort, DatasetRepository, TestCaseRepository
from evaluationimprovement.application.services.audit import AuditRecorder
from evaluationimprovement.application.views import DatasetView, TestCaseView
from evaluationimprovement.domain.dataset import EvaluationDataset
from evaluationimprovement.domain.enums import DatasetStatus
from evaluationimprovement.domain.ids import DatasetId, TestCaseId
from evaluationimprovement.domain.test_case import EvaluationTestCase


class CreateDatasetService:
    def __init__(
        self, dataset_repository: DatasetRepository, test_case_repository: TestCaseRepository,
        audit_record_repository: AuditRecordRepository, clock: ClockPort,
    ) -> None:
        self._dataset_repository = dataset_repository
        self._test_case_repository = test_case_repository
        self._clock = clock
        self._audit_recorder = AuditRecorder(audit_record_repository, clock)

    def create_dataset(self, command: CreateDatasetCommand) -> DatasetView:
        """07-data-model `evaluation_datasets` §"唯一键": `(name, version)`."""
        if self._dataset_repository.find_by_name_version(command.name, command.version) is not None:
            raise DatasetVersionConflictException(command.name, command.version)
        now = self._clock.now()
        dataset = EvaluationDataset.create(
            dataset_id=DatasetId.new_id(), name=command.name, version=command.version, domain=command.domain,
            scenario_tags=command.scenario_tags, created_by=command.created_by, now=now,
            lineage_parent_id=command.lineage_parent_id, tenant_id=command.tenant_id,
        )
        saved = self._dataset_repository.save(dataset, expected_status=None)
        self._audit_recorder.record(
            action="create_dataset", resource_type="EVALUATION_DATASET", resource_id=str(saved.dataset_id),
            actor=command.actor, outcome="SUCCESS", correlation_id=command.correlation_id,
        )
        return dataset_to_view(saved)

    def add_test_cases(self, command: AddTestCasesCommand) -> tuple[TestCaseView, ...]:
        """02-business-invariants INV-EI-005: only a mutable (DRAFT/REVIEWING) dataset
        accepts new cases. 07-data-model `evaluation_test_cases` §"唯一键":
        `(dataset_id, case_key)`.
        """
        dataset = self._dataset_repository.find_by_id(command.dataset_id)
        if dataset is None or dataset.tenant_id != command.tenant_id:
            raise DatasetNotFoundException(command.dataset_id)
        if not dataset.is_mutable:
            raise ValueError(f"dataset {command.dataset_id} is {dataset.status} and no longer accepts new test cases")

        new_cases: list[EvaluationTestCase] = []
        for case_input in command.cases:
            if self._test_case_repository.find_by_natural_key(command.dataset_id, case_input.case_key) is not None:
                raise ValueError(f"case key {case_input.case_key!r} already exists in dataset {command.dataset_id}")
            new_cases.append(EvaluationTestCase.create(
                test_case_id=TestCaseId.new_id(), dataset_id=command.dataset_id, case_key=case_input.case_key,
                scenario=case_input.scenario, user_request_redacted=case_input.user_request_redacted,
                mock_system_state=case_input.mock_system_state, ground_truth=case_input.ground_truth,
                allowed_tools=case_input.allowed_tools, forbidden_tools=case_input.forbidden_tools,
                required_approval=case_input.required_approval, verification_condition=case_input.verification_condition,
                criticality=case_input.criticality,
            ))

        self._test_case_repository.save_many(tuple(new_cases))
        total_case_count = len(self._test_case_repository.find_by_dataset(command.dataset_id))
        updated_dataset = dataset.with_case_count(total_case_count)
        self._dataset_repository.save(updated_dataset, expected_status=dataset.status)

        self._audit_recorder.record(
            action="add_test_cases", resource_type="EVALUATION_DATASET", resource_id=str(command.dataset_id),
            actor=command.actor, outcome="SUCCESS", correlation_id=command.correlation_id,
            detail=f'{{"addedCount": {len(new_cases)}}}',
        )
        return tuple(test_case_to_view(c) for c in new_cases)

    def create_next_version(self, command: CreateDatasetVersionCommand) -> DatasetView:
        """SPEC-EI-004 / 02-business-invariants INV-EI-005: "Dataset 发布后不可变；变更必须
        创建新 version，并保留 lineage." Only a PUBLISHED dataset can be versioned forward
        (a DRAFT/REVIEWING one is still mutable in place — see add_test_cases). The
        parent's own test cases are copied forward as the new version's own rows
        (never shared across dataset_id — "版本化测试资产所有权"), giving the caller a
        starting point to edit before re-publishing.
        """
        parent = self._dataset_repository.find_by_id(command.parent_dataset_id)
        if parent is None or parent.tenant_id != command.tenant_id:
            raise DatasetNotFoundException(command.parent_dataset_id)
        if parent.status != DatasetStatus.PUBLISHED:
            raise ValueError(f"dataset {command.parent_dataset_id} must be PUBLISHED to create a new version from it")
        if self._dataset_repository.find_by_name_version(parent.name, command.new_version) is not None:
            raise DatasetVersionConflictException(parent.name, command.new_version)

        now = self._clock.now()
        next_dataset = EvaluationDataset.create(
            dataset_id=DatasetId.new_id(), name=parent.name, version=command.new_version, domain=parent.domain,
            scenario_tags=parent.scenario_tags, created_by=command.created_by, now=now,
            lineage_parent_id=parent.dataset_id, tenant_id=parent.tenant_id,
        )

        parent_cases = self._test_case_repository.find_by_dataset(parent.dataset_id)
        copied_cases = tuple(
            EvaluationTestCase.create(
                test_case_id=TestCaseId.new_id(), dataset_id=next_dataset.dataset_id, case_key=c.case_key, scenario=c.scenario,
                user_request_redacted=c.user_request_redacted, mock_system_state=c.mock_system_state,
                ground_truth=c.ground_truth, allowed_tools=c.allowed_tools, forbidden_tools=c.forbidden_tools,
                required_approval=c.required_approval, verification_condition=c.verification_condition,
                criticality=c.criticality,
            )
            for c in parent_cases
        )
        self._test_case_repository.save_many(copied_cases)
        next_dataset = next_dataset.with_case_count(len(copied_cases))
        saved = self._dataset_repository.save(next_dataset, expected_status=None)

        self._audit_recorder.record(
            action="create_dataset_version", resource_type="EVALUATION_DATASET", resource_id=str(saved.dataset_id),
            actor=command.actor, outcome="SUCCESS", correlation_id=command.correlation_id,
            detail=f'{{"parentDatasetId": "{parent.dataset_id}", "copiedCaseCount": {len(copied_cases)}}}',
        )
        return dataset_to_view(saved)

    def find_dataset(self, dataset_id: DatasetId, tenant_id: str) -> DatasetView:
        dataset = self._dataset_repository.find_by_id(dataset_id)
        if dataset is None or dataset.tenant_id != tenant_id:
            raise DatasetNotFoundException(dataset_id)
        return dataset_to_view(dataset)

    def list_datasets(self, domain: str | None, status: str | None, tenant_id: str, limit: int) -> tuple[DatasetView, ...]:  # noqa: ARG002
        # 05-api-contracts: `GET /evaluation/datasets?domain=...&status=...`. status
        # filtering is not applied here — list_published() (the only DatasetRepository
        # query method 13-package-and-class-design's own port list backs) only ever
        # returns PUBLISHED datasets, which is this endpoint's own documented default.
        return tuple(dataset_to_view(d) for d in self._dataset_repository.list_published(domain, tenant_id, limit))

    def find_versions(self, name: str, tenant_id: str) -> tuple[DatasetView, ...]:
        return tuple(dataset_to_view(d) for d in self._dataset_repository.find_versions(name, tenant_id))

    def find_test_case(self, test_case_id: TestCaseId, tenant_id: str) -> TestCaseView:
        """SPEC-EI-005: the full test case schema — see TestCaseView's own docstring.
        SPEC-EI-008: the owning dataset's own tenant gates this, since
        EvaluationTestCase itself carries no tenant of its own.
        """
        case = self._test_case_repository.find_by_id(test_case_id)
        if case is None:
            raise TestCaseNotFoundException(test_case_id)
        owning_dataset = self._dataset_repository.find_by_id(case.dataset_id)
        if owning_dataset is None or owning_dataset.tenant_id != tenant_id:
            raise TestCaseNotFoundException(test_case_id)
        return test_case_to_view(case)

    def find_test_cases(self, dataset_id: DatasetId, tenant_id: str) -> tuple[TestCaseView, ...]:
        dataset = self._dataset_repository.find_by_id(dataset_id)
        if dataset is None or dataset.tenant_id != tenant_id:
            raise DatasetNotFoundException(dataset_id)
        return tuple(test_case_to_view(c) for c in self._test_case_repository.find_by_dataset(dataset_id))


def dataset_to_view(dataset: EvaluationDataset) -> DatasetView:
    return DatasetView(
        dataset_id=dataset.dataset_id, name=dataset.name, version=dataset.version, domain=dataset.domain,
        status=dataset.status, case_count=dataset.case_count, created_by=dataset.created_by,
        published_by=dataset.published_by, created_at=dataset.created_at, published_at=dataset.published_at,
        content_hash=dataset.content_hash, tenant_id=dataset.tenant_id,
    )


def test_case_to_view(case: EvaluationTestCase) -> TestCaseView:
    return TestCaseView(
        test_case_id=case.test_case_id, dataset_id=case.dataset_id, case_key=case.case_key, scenario=case.scenario,
        user_request_redacted=case.user_request_redacted, mock_system_state=case.mock_system_state,
        ground_truth=case.ground_truth, allowed_tools=case.allowed_tools, forbidden_tools=case.forbidden_tools,
        required_approval=case.required_approval, verification_condition=case.verification_condition,
        criticality=case.criticality.value, input_hash=case.input_hash,
    )
