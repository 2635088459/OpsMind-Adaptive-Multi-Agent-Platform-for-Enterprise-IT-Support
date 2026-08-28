from __future__ import annotations

import pytest

from evaluationimprovement.domain.enums import Criticality
from evaluationimprovement.domain.ids import DatasetId, TestCaseId
from evaluationimprovement.domain.test_case import EvaluationTestCase


def _build(**overrides: object) -> EvaluationTestCase:
    kwargs = dict(
        test_case_id=TestCaseId.new_id(), dataset_id=DatasetId.new_id(), case_key="duo-enrollment-expired",
        scenario="Duo enrollment expired", user_request_redacted="my MFA is broken",
        mock_system_state={"duoStatus": "EXPIRED"}, ground_truth={"classification": "MFA_ENROLLMENT_EXPIRED"},
        allowed_tools=("reset_duo_enrollment",), forbidden_tools=("disable_mfa",), required_approval=False,
        verification_condition={"duoStatus": "ACTIVE"}, criticality=Criticality.CRITICAL,
    )
    kwargs.update(overrides)
    return EvaluationTestCase.create(**kwargs)


@pytest.mark.unit
def test_input_hash_is_stable_for_identical_content() -> None:
    a = _build()
    b = _build(test_case_id=TestCaseId.new_id(), case_key="a-different-key", criticality=Criticality.STANDARD)
    # input_hash covers only reproducibility-relevant fields — case_key/criticality/id
    # are metadata, not part of the hashed evaluated content.
    assert a.input_hash == b.input_hash


@pytest.mark.unit
def test_input_hash_changes_when_ground_truth_changes() -> None:
    a = _build()
    b = _build(ground_truth={"classification": "SOMETHING_ELSE"})
    assert a.input_hash != b.input_hash


@pytest.mark.unit
def test_is_critical_reflects_criticality() -> None:
    assert _build(criticality=Criticality.CRITICAL).is_critical is True
    assert _build(criticality=Criticality.STANDARD).is_critical is False


@pytest.mark.unit
def test_a_tool_cannot_be_both_allowed_and_forbidden() -> None:
    with pytest.raises(ValueError, match="cannot be both allowed and forbidden"):
        _build(allowed_tools=("reset_duo_enrollment",), forbidden_tools=("reset_duo_enrollment",))


@pytest.mark.unit
def test_ground_truth_must_not_be_empty() -> None:
    with pytest.raises(ValueError, match="groundTruth"):
        _build(ground_truth={})
