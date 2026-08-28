"""01-domain-model §"EvaluationTestCase"."""

from __future__ import annotations

import dataclasses
import hashlib
import json
from typing import Any

from evaluationimprovement.domain.enums import Criticality
from evaluationimprovement.domain.ids import DatasetId, TestCaseId


@dataclasses.dataclass(frozen=True, slots=True)
class EvaluationTestCase:
    """`input_hash` (07-data-model) is computed from the reproducibility-relevant
    fields only (scenario/user_request/mock_system_state/ground_truth/allowed_tools/
    forbidden_tools/required_approval/verification_condition) so two cases with the
    same evaluated content always hash identically regardless of caseKey/criticality
    metadata.
    """

    test_case_id: TestCaseId
    dataset_id: DatasetId
    case_key: str
    scenario: str
    user_request_redacted: str
    mock_system_state: dict[str, Any]
    ground_truth: dict[str, Any]
    allowed_tools: tuple[str, ...]
    forbidden_tools: tuple[str, ...]
    required_approval: bool
    verification_condition: dict[str, Any]
    criticality: Criticality
    input_hash: str

    @staticmethod
    def create(
        test_case_id: TestCaseId, dataset_id: DatasetId, case_key: str, scenario: str, user_request_redacted: str,
        mock_system_state: dict[str, Any], ground_truth: dict[str, Any], allowed_tools: tuple[str, ...],
        forbidden_tools: tuple[str, ...], required_approval: bool, verification_condition: dict[str, Any],
        criticality: Criticality,
    ) -> "EvaluationTestCase":
        if not case_key or not case_key.strip():
            raise ValueError("caseKey must not be blank")
        if not scenario or not scenario.strip():
            raise ValueError("scenario must not be blank")
        if not ground_truth:
            raise ValueError("groundTruth must not be empty")
        overlap = set(allowed_tools) & set(forbidden_tools)
        if overlap:
            raise ValueError(f"a tool cannot be both allowed and forbidden: {sorted(overlap)}")
        input_hash = _compute_input_hash(
            scenario, user_request_redacted, mock_system_state, ground_truth, allowed_tools, forbidden_tools,
            required_approval, verification_condition,
        )
        return EvaluationTestCase(
            test_case_id=test_case_id, dataset_id=dataset_id, case_key=case_key, scenario=scenario,
            user_request_redacted=user_request_redacted, mock_system_state=mock_system_state, ground_truth=ground_truth,
            allowed_tools=allowed_tools, forbidden_tools=forbidden_tools, required_approval=required_approval,
            verification_condition=verification_condition, criticality=criticality, input_hash=input_hash,
        )

    @property
    def is_critical(self) -> bool:
        """02-business-invariants INV-EI-008: "Critical case 任一失败时，release gate
        必须失败."
        """
        return self.criticality is Criticality.CRITICAL


def _compute_input_hash(
    scenario: str, user_request_redacted: str, mock_system_state: dict[str, Any], ground_truth: dict[str, Any],
    allowed_tools: tuple[str, ...], forbidden_tools: tuple[str, ...], required_approval: bool,
    verification_condition: dict[str, Any],
) -> str:
    normalized = json.dumps(
        {
            "scenario": scenario, "userRequestRedacted": user_request_redacted, "mockSystemState": mock_system_state,
            "groundTruth": ground_truth, "allowedTools": sorted(allowed_tools), "forbiddenTools": sorted(forbidden_tools),
            "requiredApproval": required_approval, "verificationCondition": verification_condition,
        },
        sort_keys=True, separators=(",", ":"),
    )
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()
