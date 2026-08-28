"""01-domain-model §"值对象" and 03-state-machine. Plain str-Enum for JSON-friendly
serialization, mirroring agent-runtime-service's own domain.enums convention.
"""

from __future__ import annotations

from enum import Enum


class DatasetStatus(str, Enum):
    """03-state-machine §"EvaluationDataset": DRAFT -> REVIEWING -> PUBLISHED ->
    DEPRECATED -> ARCHIVED.
    """

    DRAFT = "DRAFT"
    REVIEWING = "REVIEWING"
    PUBLISHED = "PUBLISHED"
    DEPRECATED = "DEPRECATED"
    ARCHIVED = "ARCHIVED"


class Criticality(str, Enum):
    """01-domain-model §"EvaluationTestCase": "criticality=CRITICAL 的 case 失败时，
    release gate 必须失败."
    """

    CRITICAL = "CRITICAL"
    STANDARD = "STANDARD"


class RunStatus(str, Enum):
    """03-state-machine §"EvaluationRun": QUEUED -> RUNNING -> SCORING -> COMPARING ->
    PASSED, with RUNNING/SCORING/COMPARING -> FAILED, RUNNING -> PARTIAL, and
    QUEUED/RUNNING -> CANCELLED. PASSED/FAILED/CANCELLED are final.
    """

    QUEUED = "QUEUED"
    RUNNING = "RUNNING"
    SCORING = "SCORING"
    COMPARING = "COMPARING"
    PASSED = "PASSED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"
    PARTIAL = "PARTIAL"


class CaseExecutionStatus(str, Enum):
    """SPEC-EI-009 / 10-failure-handling §"Partial Run": "Partial report 必须列出：未执行
    case；缺失 score dimension；runner error." Whether one case's own
    AgentRuntimeEvaluationPort.execute_case() call actually produced real, scoreable
    execution data (COMPLETED), raised an exception (FAILED — the "runner error"
    case), or was never attempted at all (SKIPPED — the "未执行 case" case). Only
    COMPLETED cases are ever eligible for score_case(); FAILED/SKIPPED cases are
    still "accounted for" by finalize_scoring() so a runner error can never leave a
    run stuck in SCORING forever (see IncompleteRunException's own docstring, which
    named exactly this behavior before it was ever implemented).
    """

    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    SKIPPED = "SKIPPED"


class EvaluationDimension(str, Enum):
    """01-domain-model §"值对象": `EvaluationDimension`."""

    CLASSIFICATION_ACCURACY = "CLASSIFICATION_ACCURACY"
    ROOT_CAUSE_ACCURACY = "ROOT_CAUSE_ACCURACY"
    TOOL_SELECTION = "TOOL_SELECTION"
    TOOL_ARGUMENTS = "TOOL_ARGUMENTS"
    POLICY_COMPLIANCE = "POLICY_COMPLIANCE"
    MEMORY_RETRIEVAL_PRECISION = "MEMORY_RETRIEVAL_PRECISION"
    RESOLUTION_SUCCESS = "RESOLUTION_SUCCESS"
    REOPEN_RATE = "REOPEN_RATE"
    HUMAN_ESCALATION_RATE = "HUMAN_ESCALATION_RATE"
    TOKEN_COST = "TOKEN_COST"
    LATENCY = "LATENCY"
    HANDOFF_COMPLETENESS = "HANDOFF_COMPLETENESS"


class GraderType(str, Enum):
    """01-domain-model §"值对象": `GraderType`. 02-business-invariants INV-EI-003:
    "安全相关指标必须使用 deterministic grader 判定；LLM Judge 只能作为辅助质量评分."
    """

    DETERMINISTIC = "DETERMINISTIC"
    LLM_JUDGE = "LLM_JUDGE"
    HYBRID = "HYBRID"
    HUMAN_REVIEW = "HUMAN_REVIEW"


class CandidateStatus(str, Enum):
    """03-state-machine §"ImprovementCandidate": DRAFT -> BENCHMARKING ->
    PENDING_APPROVAL -> APPROVED -> CANARYING -> PROMOTED, with BENCHMARKING/
    PENDING_APPROVAL -> REJECTED and CANARYING/PROMOTED -> ROLLED_BACK.
    """

    DRAFT = "DRAFT"
    BENCHMARKING = "BENCHMARKING"
    REJECTED = "REJECTED"
    PENDING_APPROVAL = "PENDING_APPROVAL"
    APPROVED = "APPROVED"
    CANARYING = "CANARYING"
    PROMOTED = "PROMOTED"
    ROLLED_BACK = "ROLLED_BACK"


class CanaryStatus(str, Enum):
    """03-state-machine §"Canary": PLANNED -> ACTIVE -> EXPANDING -> SUCCEEDED, with
    ACTIVE/EXPANDING -> PAUSED/FAILED, FAILED -> ROLLBACK_REQUESTED ->
    ROLLED_BACK.
    """

    PLANNED = "PLANNED"
    ACTIVE = "ACTIVE"
    EXPANDING = "EXPANDING"
    SUCCEEDED = "SUCCEEDED"
    PAUSED = "PAUSED"
    FAILED = "FAILED"
    ROLLBACK_REQUESTED = "ROLLBACK_REQUESTED"
    ROLLED_BACK = "ROLLED_BACK"


class GateDecision(str, Enum):
    """04-use-cases UC-EI-003: "输出 PASSED 或 FAILED"."""

    PASSED = "PASSED"
    FAILED = "FAILED"


class RiskLevel(str, Enum):
    """01-domain-model §"ImprovementCandidate": `riskLevel`. 11-security: "高风险
    candidate 必须走 06 的职责分离与审批."
    """

    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"


class CandidateType(str, Enum):
    """04-use-cases UC-EI-004: "生成 prompt、routing、tool schema hint、memory retrieval
    config 或 verification checklist 变更建议".
    """

    PROMPT_CHANGE = "PROMPT_CHANGE"
    ROUTING_CHANGE = "ROUTING_CHANGE"
    TOOL_SCHEMA_HINT = "TOOL_SCHEMA_HINT"
    MEMORY_RETRIEVAL_CONFIG = "MEMORY_RETRIEVAL_CONFIG"
    VERIFICATION_CHECKLIST = "VERIFICATION_CHECKLIST"


class ScoreFailureCode(str, Enum):
    """10-failure-handling: "Deterministic grader failure：对应 dimension 标记
    GRADER_ERROR"; "LLM Judge failure：质量类 dimension 可标记 UNSCORED"; 09-concurrency-
    and-idempotency: "结果标记为 STALE_RESULT，不得进入 gate 计算". THRESHOLD_NOT_MET covers
    the ordinary "graded fine, did not clear the bar" case.
    """

    THRESHOLD_NOT_MET = "THRESHOLD_NOT_MET"
    GRADER_ERROR = "GRADER_ERROR"
    UNSCORED = "UNSCORED"
    STALE_RESULT = "STALE_RESULT"


class OutboxStatus(str, Enum):
    PENDING = "PENDING"
    PUBLISHED = "PUBLISHED"
    FAILED = "FAILED"
    DEAD_LETTER = "DEAD_LETTER"
