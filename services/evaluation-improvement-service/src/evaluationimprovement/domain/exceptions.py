"""Domain exceptions — raised by aggregates using only information the aggregate
already carries (no I/O). Distinct from application-layer exceptions
(evaluationimprovement.application.exceptions), which are raised after a repository
lookup a pure domain function must not perform.
"""

from __future__ import annotations


class SelfReviewNotAllowedException(RuntimeError):
    """14-testing-strategy §"Security Tests": "author 不能 publish 自己未 review 的
    dataset." A dataset's publisher must differ from its creator.
    """

    def __init__(self) -> None:
        super().__init__("a dataset cannot be published by the same actor who created it")


class DatasetHasNoTestCasesException(RuntimeError):
    def __init__(self) -> None:
        super().__init__("a dataset cannot be published with zero test cases")


class MissingVersionBindingException(RuntimeError):
    """domain-rules: "在缺失 source linkage、version、hash 或 correlation id 时产出 passed
    gate" is forbidden — enforced at construction, not just at gate-evaluation time.
    """

    def __init__(self, field_name: str) -> None:
        super().__init__(f"evaluation fact is missing required version binding field: {field_name}")


class SelfApprovalNotAllowedException(RuntimeError):
    """11-security: "自动生成的 candidate 不能自我审批." 14-testing-strategy §"Security
    Tests": "candidate creator 不能 approve 自己 candidate."
    """

    def __init__(self) -> None:
        super().__init__("an improvement candidate cannot be approved by the same actor who created it")


class CandidateMissingBenchmarkException(RuntimeError):
    """02-business-invariants INV-EI-002: "任何 candidate promotion 之前必须通过 release
    gate."
    """

    def __init__(self) -> None:
        super().__init__("an improvement candidate cannot request approval before it has a passing benchmark result")


class CandidateMissingApprovalException(RuntimeError):
    """02-business-invariants INV-EI-002: candidate promotion requires 06 governance
    approval before Canary can start.
    """

    def __init__(self) -> None:
        super().__init__("an improvement candidate cannot start canary before it has an approval reference")
