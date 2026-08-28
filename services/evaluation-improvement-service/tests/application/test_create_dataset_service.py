from __future__ import annotations

import pytest

from evaluationimprovement.application.commands import (
    AddTestCasesCommand,
    CreateDatasetCommand,
    CreateDatasetVersionCommand,
    PublishDatasetCommand,
    SubmitDatasetForReviewCommand,
    TestCaseInput,
)
from evaluationimprovement.application.exceptions import (
    DatasetNotFoundException,
    DatasetVersionConflictException,
    TestCaseNotFoundException,
)
from evaluationimprovement.container import Container
from evaluationimprovement.domain.enums import Criticality
from evaluationimprovement.domain.ids import DatasetId, TestCaseId


def _create(container: Container, name: str = "identity-mfa-golden", version: str = "2026.08.1"):
    return container.create_dataset_service.create_dataset(CreateDatasetCommand(
        name=name, version=version, domain="IDENTITY_ACCESS", scenario_tags=("mfa",), created_by="author-1",
        actor="author-1", correlation_id="corr-1",
    ))


def _case(case_key: str = "duo-enrollment-expired") -> TestCaseInput:
    return TestCaseInput(
        case_key=case_key, scenario="Duo enrollment expired", user_request_redacted="mfa broken",
        mock_system_state={"duoStatus": "EXPIRED"}, ground_truth={"classification": "MFA_ENROLLMENT_EXPIRED"},
        allowed_tools=("reset_duo_enrollment",), forbidden_tools=("disable_mfa",), required_approval=False,
        verification_condition={"duoStatus": "ACTIVE"}, criticality=Criticality.CRITICAL,
    )


def _published_dataset(container: Container, name: str = "identity-mfa-golden", version: str = "2026.08.1"):
    dataset = _create(container, name, version)
    container.create_dataset_service.add_test_cases(AddTestCasesCommand(
        dataset_id=dataset.dataset_id, cases=(_case(),), actor="author-1", correlation_id="corr-1",
    ))
    container.publish_dataset_service.submit_for_review(SubmitDatasetForReviewCommand(
        dataset_id=dataset.dataset_id, actor="author-1", correlation_id="corr-1",
    ))
    return container.publish_dataset_service.publish(PublishDatasetCommand(
        dataset_id=dataset.dataset_id, published_by="reviewer-1", actor="reviewer-1", correlation_id="corr-1",
    ))


@pytest.mark.unit
def test_create_dataset_happy_path(container: Container) -> None:
    view = _create(container)
    assert view.status.value == "DRAFT"
    assert view.case_count == 0


@pytest.mark.unit
def test_duplicate_name_version_is_rejected(container: Container) -> None:
    _create(container)
    with pytest.raises(DatasetVersionConflictException):
        _create(container)


@pytest.mark.unit
def test_add_test_cases_updates_case_count(container: Container) -> None:
    dataset = _create(container)
    case = TestCaseInput(
        case_key="duo-enrollment-expired", scenario="Duo enrollment expired", user_request_redacted="mfa broken",
        mock_system_state={"duoStatus": "EXPIRED"}, ground_truth={"classification": "MFA_ENROLLMENT_EXPIRED"},
        allowed_tools=("reset_duo_enrollment",), forbidden_tools=("disable_mfa",), required_approval=False,
        verification_condition={"duoStatus": "ACTIVE"}, criticality=Criticality.CRITICAL,
    )
    container.create_dataset_service.add_test_cases(AddTestCasesCommand(
        dataset_id=dataset.dataset_id, cases=(case,), actor="author-1", correlation_id="corr-1",
    ))
    refreshed = container.create_dataset_service.find_dataset(dataset.dataset_id, "default")
    assert refreshed.case_count == 1


@pytest.mark.unit
def test_duplicate_case_key_in_same_dataset_is_rejected(container: Container) -> None:
    dataset = _create(container)
    case = TestCaseInput(
        case_key="duo-enrollment-expired", scenario="s", user_request_redacted="", mock_system_state={},
        ground_truth={"classification": "X"}, allowed_tools=(), forbidden_tools=(), required_approval=False,
        verification_condition={}, criticality=Criticality.STANDARD,
    )
    container.create_dataset_service.add_test_cases(AddTestCasesCommand(
        dataset_id=dataset.dataset_id, cases=(case,), actor="author-1", correlation_id="corr-1",
    ))
    with pytest.raises(ValueError, match="already exists"):
        container.create_dataset_service.add_test_cases(AddTestCasesCommand(
            dataset_id=dataset.dataset_id, cases=(case,), actor="author-1", correlation_id="corr-1",
        ))


@pytest.mark.unit
def test_create_next_version_copies_parent_cases_forward(container: Container) -> None:
    """02-business-invariants INV-EI-005: a new version starts as a DRAFT, linked to
    its parent via lineage_parent_id, with the parent's own test cases copied forward
    as new rows scoped to the new dataset_id — never shared across dataset_id.
    """
    published = _published_dataset(container)
    next_version = container.create_dataset_service.create_next_version(CreateDatasetVersionCommand(
        parent_dataset_id=published.dataset_id, new_version="2026.09.1", created_by="author-2", actor="author-2",
        correlation_id="corr-1",
    ))
    assert next_version.status.value == "DRAFT"
    assert next_version.version == "2026.09.1"
    assert next_version.name == published.name
    assert next_version.case_count == 1

    copied_case = container.test_case_repository.find_by_natural_key(next_version.dataset_id, "duo-enrollment-expired")
    assert copied_case is not None
    assert copied_case.dataset_id == next_version.dataset_id
    # The parent's own case row is untouched — a distinct row, not shared.
    parent_case = container.test_case_repository.find_by_natural_key(published.dataset_id, "duo-enrollment-expired")
    assert parent_case is not None
    assert parent_case.test_case_id != copied_case.test_case_id


@pytest.mark.unit
def test_create_next_version_requires_a_published_parent(container: Container) -> None:
    draft = _create(container)
    with pytest.raises(ValueError, match="must be PUBLISHED"):
        container.create_dataset_service.create_next_version(CreateDatasetVersionCommand(
            parent_dataset_id=draft.dataset_id, new_version="2026.09.1", created_by="author-1", actor="author-1",
            correlation_id="corr-1",
        ))


@pytest.mark.unit
def test_create_next_version_rejects_a_reused_version_string(container: Container) -> None:
    published = _published_dataset(container)
    with pytest.raises(DatasetVersionConflictException):
        container.create_dataset_service.create_next_version(CreateDatasetVersionCommand(
            parent_dataset_id=published.dataset_id, new_version=published.version, created_by="author-2", actor="author-2",
            correlation_id="corr-1",
        ))


@pytest.mark.unit
def test_create_next_version_requires_an_existing_parent(container: Container) -> None:
    with pytest.raises(DatasetNotFoundException):
        container.create_dataset_service.create_next_version(CreateDatasetVersionCommand(
            parent_dataset_id=DatasetId.new_id(), new_version="1", created_by="author-1", actor="author-1",
            correlation_id="corr-1",
        ))


@pytest.mark.unit
def test_find_versions_returns_the_full_lineage_chain_oldest_first(container: Container) -> None:
    first = _published_dataset(container, name="identity-mfa-golden", version="2026.08.1")
    second = container.create_dataset_service.create_next_version(CreateDatasetVersionCommand(
        parent_dataset_id=first.dataset_id, new_version="2026.09.1", created_by="author-2", actor="author-2",
        correlation_id="corr-1",
    ))

    versions = container.create_dataset_service.find_versions("identity-mfa-golden", "default")
    assert [v.dataset_id for v in versions] == [first.dataset_id, second.dataset_id]
    assert versions[1].status.value == "DRAFT"


@pytest.mark.unit
def test_find_test_case_exposes_the_full_schema(container: Container) -> None:
    """SPEC-EI-005: ground truth, tool allow/deny lists, verification condition, and
    approval expectation must all be readable back, not just the truncated
    create-response fields.
    """
    published = _published_dataset(container)
    cases = container.create_dataset_service.find_test_cases(published.dataset_id, "default")
    assert len(cases) == 1
    full = container.create_dataset_service.find_test_case(cases[0].test_case_id, "default")
    assert full.ground_truth == {"classification": "MFA_ENROLLMENT_EXPIRED"}
    assert full.allowed_tools == ("reset_duo_enrollment",)
    assert full.forbidden_tools == ("disable_mfa",)
    assert full.verification_condition == {"duoStatus": "ACTIVE"}
    assert full.required_approval is False
    assert full.scenario == "Duo enrollment expired"


@pytest.mark.unit
def test_find_test_case_unknown_id_raises_not_found(container: Container) -> None:
    with pytest.raises(TestCaseNotFoundException):
        container.create_dataset_service.find_test_case(TestCaseId.new_id(), "default")


@pytest.mark.unit
def test_find_test_cases_requires_an_existing_dataset(container: Container) -> None:
    with pytest.raises(DatasetNotFoundException):
        container.create_dataset_service.find_test_cases(DatasetId.new_id(), "default")


@pytest.mark.unit
def test_find_dataset_from_a_different_tenant_reads_back_as_not_found(container: Container) -> None:
    """SPEC-EI-008 / 11-security: "07 依赖 01 提供 ... tenant scope." A dataset owned by
    one tenant must never be visible to a caller asserting a different tenant — and
    the failure mode is NotFound, never a 403, so a wrong tenant can't even confirm
    the dataset exists.
    """
    dataset = container.create_dataset_service.create_dataset(CreateDatasetCommand(
        name="tenant-a-dataset", version="1", domain="IDENTITY_ACCESS", scenario_tags=(), created_by="author-1",
        actor="author-1", correlation_id="corr-1", tenant_id="tenant-a",
    ))
    found = container.create_dataset_service.find_dataset(dataset.dataset_id, "tenant-a")
    assert found.dataset_id == dataset.dataset_id

    with pytest.raises(DatasetNotFoundException):
        container.create_dataset_service.find_dataset(dataset.dataset_id, "tenant-b")


@pytest.mark.unit
def test_list_datasets_and_find_versions_never_cross_tenants(container: Container) -> None:
    _published_dataset(container, name="tenant-a-published")
    tenant_b_dataset = container.create_dataset_service.create_dataset(CreateDatasetCommand(
        name="tenant-b-published", version="1", domain="IDENTITY_ACCESS", scenario_tags=(), created_by="author-1",
        actor="author-1", correlation_id="corr-1", tenant_id="tenant-b",
    ))
    container.create_dataset_service.add_test_cases(AddTestCasesCommand(
        dataset_id=tenant_b_dataset.dataset_id, cases=(_case(),), actor="author-1", correlation_id="corr-1", tenant_id="tenant-b",
    ))
    container.publish_dataset_service.submit_for_review(SubmitDatasetForReviewCommand(
        dataset_id=tenant_b_dataset.dataset_id, actor="author-1", correlation_id="corr-1", tenant_id="tenant-b",
    ))
    container.publish_dataset_service.publish(PublishDatasetCommand(
        dataset_id=tenant_b_dataset.dataset_id, published_by="reviewer-1", actor="reviewer-1", correlation_id="corr-1",
        tenant_id="tenant-b",
    ))

    default_tenant_names = {v.name for v in container.create_dataset_service.list_datasets(None, None, "default", 50)}
    tenant_b_names = {v.name for v in container.create_dataset_service.list_datasets(None, None, "tenant-b", 50)}
    assert "tenant-a-published" in default_tenant_names
    assert "tenant-b-published" not in default_tenant_names
    assert tenant_b_names == {"tenant-b-published"}

    assert container.create_dataset_service.find_versions("tenant-b-published", "default") == ()
    assert len(container.create_dataset_service.find_versions("tenant-b-published", "tenant-b")) == 1


@pytest.mark.unit
def test_find_test_case_from_a_different_tenant_reads_back_as_not_found(container: Container) -> None:
    published = _published_dataset(container, name="tenant-scoped-cases")
    cases = container.create_dataset_service.find_test_cases(published.dataset_id, "default")
    with pytest.raises(TestCaseNotFoundException):
        container.create_dataset_service.find_test_case(cases[0].test_case_id, "tenant-b")
    with pytest.raises(DatasetNotFoundException):
        container.create_dataset_service.find_test_cases(published.dataset_id, "tenant-b")


@pytest.mark.unit
def test_a_dataset_created_in_one_tenant_cannot_be_mutated_by_another(container: Container) -> None:
    """Same tenant isolation, applied to the write side — add_test_cases/
    submit_for_review both reuse the same `_require_dataset`/tenant-check as reads.
    """
    dataset = container.create_dataset_service.create_dataset(CreateDatasetCommand(
        name="isolated-writes", version="1", domain="IDENTITY_ACCESS", scenario_tags=(), created_by="author-1",
        actor="author-1", correlation_id="corr-1", tenant_id="tenant-a",
    ))
    with pytest.raises(DatasetNotFoundException):
        container.create_dataset_service.add_test_cases(AddTestCasesCommand(
            dataset_id=dataset.dataset_id, cases=(_case(),), actor="author-1", correlation_id="corr-1", tenant_id="tenant-b",
        ))
    with pytest.raises(DatasetNotFoundException):
        container.publish_dataset_service.submit_for_review(SubmitDatasetForReviewCommand(
            dataset_id=dataset.dataset_id, actor="author-1", correlation_id="corr-1", tenant_id="tenant-b",
        ))
