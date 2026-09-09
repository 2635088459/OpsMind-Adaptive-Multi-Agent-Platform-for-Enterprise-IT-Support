"""SPEC-EI-013 follow-up: wire contract for the evaluation execute-case endpoint,
matched to evaluation-improvement-service's own HttpAgentRuntimeEvaluationAdapter
(camelCase, `costTokens`/`latencyMs` etc.). Only the fields that adapter actually
sends / reads are modelled; everything else it defaults.
"""

from __future__ import annotations

from pydantic import BaseModel, Field


class ExecuteEvaluationCaseRequest(BaseModel):
    runId: str
    runGeneration: int = 1
    targetVersion: str = ""
    testCaseId: str = ""
    caseKey: str = ""
    scenario: str = ""
    userRequestRedacted: str = ""
    # accepted for contract-compatibility with the eval client; not used by the
    # reasoning-only execution path (no mock tools are actually invoked here).
    mockSystemState: dict = Field(default_factory=dict)
    allowedTools: list[str] = Field(default_factory=list)
    forbiddenTools: list[str] = Field(default_factory=list)
    requiredApproval: bool = False
    # evaluation-improvement-service's EvaluationTestCase types this as a dict; accepted
    # for contract-compatibility, not used by the reasoning-only execution path.
    verificationCondition: dict = Field(default_factory=dict)


class ExecuteEvaluationCaseResponse(BaseModel):
    finalState: str
    classification: str
    toolCalls: list[str]
    costTokens: int
    promptTokens: int
    completionTokens: int
    latencyMs: int
    policyViolationCount: int = 0
    forbiddenToolCallCount: int = 0
    unauthorizedMemoryAccessCount: int = 0
    approvalTriggered: bool = False
    verificationPassed: bool = True
    toolCallArgs: dict = Field(default_factory=dict)
    explanationText: str = ""
    workflowTraceRef: str = ""
