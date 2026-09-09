"""13-package-and-class-design §"Interfaces": the SPEC-EI-013-follow-up evaluation
execute-case endpoint. Internal, service-to-service (evaluation-improvement-service's
own HttpAgentRuntimeEvaluationAdapter, no employee JWT) — same trusted-internal posture
as the `/internal/agent-runtime/v1/...` routes, kept at the path that adapter already
targets (`/agent-runtime/evaluation/execute-case`). Depends only on
ExecuteEvaluationCasePort.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends

from agentruntime.application.commands import ExecuteEvaluationCaseCommand
from agentruntime.application.ports_in import ExecuteEvaluationCasePort
from agentruntime.container import get_execute_evaluation_case_port
from agentruntime.interfaces.evaluation.schemas import ExecuteEvaluationCaseRequest, ExecuteEvaluationCaseResponse

router = APIRouter(prefix="/agent-runtime/evaluation", tags=["evaluation"])


@router.post("/execute-case", response_model=ExecuteEvaluationCaseResponse)
def execute_case(
    request: ExecuteEvaluationCaseRequest,
    port: ExecuteEvaluationCasePort = Depends(get_execute_evaluation_case_port),
) -> ExecuteEvaluationCaseResponse:
    view = port.execute_case(ExecuteEvaluationCaseCommand(
        run_id=request.runId,
        run_generation=request.runGeneration,
        target_version=request.targetVersion,
        test_case_id=request.testCaseId,
        case_key=request.caseKey,
        scenario=request.scenario,
        message=request.userRequestRedacted or request.scenario,
    ))
    return ExecuteEvaluationCaseResponse(
        finalState=view.final_state,
        classification=view.classification,
        toolCalls=list(view.tool_calls),
        costTokens=view.prompt_tokens + view.completion_tokens,
        promptTokens=view.prompt_tokens,
        completionTokens=view.completion_tokens,
        latencyMs=view.latency_ms,
        approvalTriggered=bool(request.requiredApproval and view.tool_calls),
        explanationText=view.explanation_text,
    )
