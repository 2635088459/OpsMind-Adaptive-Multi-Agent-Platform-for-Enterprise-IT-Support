"""SPEC-EI-014: GraderRegistry.dimensions_for_case() — which dimensions a given case
actually gets requested for, now that the registry carries the full deterministic
catalog.
"""

from __future__ import annotations

import pytest

from evaluationimprovement.domain.enums import Criticality, EvaluationDimension, GraderType
from evaluationimprovement.domain.ids import DatasetId, TestCaseId
from evaluationimprovement.domain.test_case import EvaluationTestCase
from evaluationimprovement.infrastructure.graders.registry import GraderRegistry


def _test_case(**overrides) -> EvaluationTestCase:
    defaults = dict(
        test_case_id=TestCaseId.new_id(), dataset_id=DatasetId.new_id(), case_key="k1", scenario="s",
        user_request_redacted="", mock_system_state={}, ground_truth={"classification": "MFA_ENROLLMENT_EXPIRED"},
        allowed_tools=("reset_duo_enrollment",), forbidden_tools=(), required_approval=False,
        verification_condition={}, criticality=Criticality.STANDARD,
    )
    defaults.update(overrides)
    return EvaluationTestCase.create(**defaults)


@pytest.mark.unit
def test_a_minimal_case_still_gets_policy_resolution_and_handoff_dimensions() -> None:
    registry = GraderRegistry()
    test_case = _test_case(ground_truth={"note": "no classification/rootCause declared"}, allowed_tools=(), forbidden_tools=())
    dims = {d for d, _ in registry.dimensions_for_case(test_case)}
    assert dims == {
        EvaluationDimension.POLICY_COMPLIANCE, EvaluationDimension.RESOLUTION_SUCCESS,
        EvaluationDimension.HANDOFF_COMPLETENESS,
    }


@pytest.mark.unit
def test_a_fully_specified_case_requests_every_deterministic_dimension_plus_handoff() -> None:
    registry = GraderRegistry()
    test_case = _test_case(ground_truth={
        "classification": "MFA_ENROLLMENT_EXPIRED", "rootCause": "duo_token_expired",
        "expectedToolArgs": {"reset_duo_enrollment": {"userId": "u-1"}},
    })
    pairs = registry.dimensions_for_case(test_case)
    dims = {d for d, _ in pairs}
    assert dims == {
        EvaluationDimension.CLASSIFICATION_ACCURACY, EvaluationDimension.TOOL_SELECTION,
        EvaluationDimension.ROOT_CAUSE_ACCURACY, EvaluationDimension.POLICY_COMPLIANCE,
        EvaluationDimension.RESOLUTION_SUCCESS, EvaluationDimension.TOOL_ARGUMENTS,
        EvaluationDimension.HANDOFF_COMPLETENESS,
    }
    deterministic_dims = {d for d, gt in pairs if gt == GraderType.DETERMINISTIC}
    assert EvaluationDimension.HANDOFF_COMPLETENESS not in deterministic_dims
    judge_pairs = [(d, gt) for d, gt in pairs if gt == GraderType.LLM_JUDGE]
    assert judge_pairs == [(EvaluationDimension.HANDOFF_COMPLETENESS, GraderType.LLM_JUDGE)]
