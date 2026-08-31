"""Application-layer exceptions — raised after I/O a pure domain function must not
perform (repository lookups, uniqueness checks, persisted-state comparisons).
"""

from __future__ import annotations


class DatasetNotFoundException(RuntimeError):
    def __init__(self, dataset_id: object) -> None:
        super().__init__(f"dataset {dataset_id} was not found")


class TestCaseNotFoundException(RuntimeError):
    def __init__(self, test_case_id: object) -> None:
        super().__init__(f"test case {test_case_id} was not found")


class RunNotFoundException(RuntimeError):
    def __init__(self, run_id: object) -> None:
        super().__init__(f"evaluation run {run_id} was not found")


class ReportNotFoundException(RuntimeError):
    def __init__(self, report_id: object) -> None:
        super().__init__(f"regression report {report_id} was not found")


class CandidateNotFoundException(RuntimeError):
    def __init__(self, candidate_id: object) -> None:
        super().__init__(f"improvement candidate {candidate_id} was not found")


class GatePolicyNotFoundException(RuntimeError):
    def __init__(self, gate_policy: str) -> None:
        super().__init__(f"gate policy {gate_policy!r} was not found")


class DatasetVersionConflictException(RuntimeError):
    """07-data-model `evaluation_datasets` §"唯一键": `(name, version)`."""

    def __init__(self, name: str, version: str) -> None:
        super().__init__(f"dataset {name!r} version {version!r} already exists")


class RunKeyConflictException(RuntimeError):
    """09-concurrency-and-idempotency §"并发规则": "同一个 runKey 重复提交必须返回同一 run" —
    raised only when the same runKey is reused with genuinely different request
    parameters; an identical resubmission instead returns the existing run.
    """

    def __init__(self, run_key: str) -> None:
        super().__init__(f"runKey {run_key!r} was already used with different parameters")


class IdempotencyKeyReusedException(RuntimeError):
    def __init__(self, idempotency_key: object) -> None:
        super().__init__(f"idempotency key {idempotency_key} was already used with a different request")


class OptimisticConcurrencyConflictException(RuntimeError):
    def __init__(self, resource: str) -> None:
        super().__init__(f"{resource} was modified concurrently")


class UnauthorizedActionException(RuntimeError):
    """11-security §"身份与权限": actor lacks the required role for this action."""

    def __init__(self, action: str, actor: str) -> None:
        super().__init__(f"actor {actor!r} is not authorized to perform {action!r}")


class BaselineRunNotFoundException(RuntimeError):
    """10-failure-handling §"Partial Run": a baseline run id was requested but does not
    exist or is not itself a terminal run.
    """

    def __init__(self, baseline_run_id: object) -> None:
        super().__init__(f"baseline run {baseline_run_id} was not found or is not final")


class StaleResultException(RuntimeError):
    """09-concurrency-and-idempotency §"Stale 结果": "如果 case runner 返回的 runGeneration
    与当前 run generation 不一致，结果标记为 STALE_RESULT，不得进入 gate 计算."
    """

    def __init__(self, run_id: object, expected_generation: int, actual_generation: int) -> None:
        super().__init__(
            f"stale result for run {run_id}: expected generation {expected_generation}, got {actual_generation}"
        )


class IncompleteRunException(RuntimeError):
    """08-transaction-and-outbox §"Run 完成事务": every expected case must have a score
    or be explicitly marked skipped/failed before a run can leave SCORING.
    """

    def __init__(self, run_id: object, missing_case_count: int) -> None:
        super().__init__(f"run {run_id} still has {missing_case_count} test case(s) without a recorded score")


class GraderNotFoundException(RuntimeError):
    """10-failure-handling §"Grader Failure": no registered grader covers this
    dimension/grader-type pair.
    """

    def __init__(self, dimension: object, grader_type: object) -> None:
        super().__init__(f"no grader registered for dimension={dimension} grader_type={grader_type}")


class PolicyApprovalUnavailableException(RuntimeError):
    """SPEC-EI-026 (policy-approval-release-contract): raised when the real
    HttpPolicyApprovalAdapter's call to 06-policy-approval-governance fails (timeout,
    connection refused, non-2xx status, malformed response body) — one application-
    layer type so interfaces.errors can map it to `503 DEPENDENCY_UNAVAILABLE`
    without importing the infrastructure adapter directly (interfaces must not depend
    on infrastructure — see pyproject.toml's own import-linter contracts). Mirrors
    GraderNotFoundException's own shape/handling exactly.
    """

    def __init__(self, candidate_id: object, reason: str) -> None:
        super().__init__(f"policy approval request for candidate {candidate_id} failed: {reason}")


class PoisonApprovalDecisionEventException(RuntimeError):
    """SPEC-EI-035 (langsmith-grader-outbox-failure-recovery) / 10-failure-handling
    §"Poison Event": raised after a genuinely late/conflicting `approval.granted.v1`/
    `approval.denied.v1` decision has already been recorded to
    PoisonEventRepository — see ConsumeApprovalDecisionEventService's own module
    docstring. Never raised for a redelivered event (ProcessedEventRepository's own
    dedup check already short-circuits that before this is ever reachable).
    """

    def __init__(self, event_id: str, reason: str) -> None:
        super().__init__(f"approval decision event {event_id} could not be applied: {reason}")


class CaseExecutionNotCompletedException(RuntimeError):
    """SPEC-EI-009: only a COMPLETED CaseExecutionResult carries real, scoreable
    execution data — scoring a FAILED/SKIPPED one would grade default/empty
    placeholder fields as if they were a genuine agent run.
    """

    def __init__(self, test_case_id: object, status: object) -> None:
        super().__init__(f"test case {test_case_id} cannot be scored: its case execution status is {status}, not COMPLETED")
