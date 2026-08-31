"""SPEC-EI-022 (ci-evaluation-gate-harness) 14-testing-strategy §"Acceptance
Criteria": "CI 能以非零退出或 failed status 阻止 promotion." The actual CLI entry point a
pipeline invokes (`uv run evaluation-ci-gate ...` — see pyproject.toml's own
`[project.scripts]`); all the real logic lives in
application.services.ci_evaluation_gate.CiEvaluationGateService, tested there against
the real container. This module only parses argv, builds the one Container a CI job
needs, and translates CiGateOutcome into an exit code + a human/machine-readable
summary line.
"""

from __future__ import annotations

import argparse
import json
import sys
import uuid

from evaluationimprovement.application.commands import RunCiGateCommand
from evaluationimprovement.container import get_container
from evaluationimprovement.domain.ids import DatasetId, RunId


def _parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="evaluation-ci-gate", description="Run an evaluation benchmark to completion and gate CI on the result.",
    )
    parser.add_argument("--run-key", required=True, help="Idempotency key for the run — resubmitting the same key returns the same run.")
    parser.add_argument("--dataset-id", required=True, help="UUID of the published EvaluationDataset to benchmark against.")
    parser.add_argument("--target-version", required=True, help="Version identifier of the system under test.")
    parser.add_argument("--baseline-version", default=None, help="Version identifier metadata only — not a run to diff against, see --baseline-run-id.")
    parser.add_argument("--baseline-run-id", default=None, help="UUID of a specific terminal PASSED run to diff against.")
    parser.add_argument("--grader-bundle-version", required=True)
    parser.add_argument("--policy-version", required=True)
    parser.add_argument("--gate-policy", required=True, help='e.g. "mvp-release-gate-v1".')
    parser.add_argument("--triggered-by", default="ci")
    parser.add_argument("--actor", default="ci")
    parser.add_argument("--correlation-id", default=None, help="Defaults to a fresh UUID when omitted.")
    parser.add_argument("--max-iterations", type=int, default=5)
    parser.add_argument("--batch-size", type=int, default=50)
    parser.add_argument("--json", action="store_true", help="Print the outcome as one JSON line instead of human-readable text.")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(sys.argv[1:] if argv is None else argv)
    # SPEC-EI-022: reaches the same process-wide singleton every other entry point
    # (main.py's own FastAPI app, interfaces.rest.router) reaches through
    # container.get_container() — a real deployment invokes this CLI as a genuinely
    # separate process either way, so the shared-cache semantics only matter for
    # tests, which pre-seed data through this exact same accessor.
    container = get_container()
    command = RunCiGateCommand(
        run_key=args.run_key, dataset_id=DatasetId(uuid.UUID(args.dataset_id)), target_version=args.target_version,
        baseline_version=args.baseline_version, grader_bundle_version=args.grader_bundle_version,
        policy_version=args.policy_version, gate_policy=args.gate_policy, triggered_by=args.triggered_by,
        actor=args.actor, correlation_id=args.correlation_id or str(uuid.uuid4()),
        baseline_run_id=RunId(uuid.UUID(args.baseline_run_id)) if args.baseline_run_id else None,
        max_iterations=args.max_iterations, batch_size=args.batch_size,
    )
    try:
        outcome = container.ci_evaluation_gate_service.run_gate(command)
    except Exception as exc:  # noqa: BLE001 - a CI-facing CLI must never crash with a raw traceback; any failure here is "gate not passed."
        if args.json:
            print(json.dumps({"passed": False, "reason": str(exc)}))
        else:
            print(f"evaluation-ci-gate failed: {exc}", file=sys.stderr)
        return 1

    if args.json:
        print(json.dumps({
            "runId": str(outcome.run_id), "runStatus": outcome.run_status, "gateDecision": outcome.gate_decision,
            "criticalFailures": list(outcome.critical_failures), "reason": outcome.reason, "passed": outcome.passed,
        }))
    else:
        print(f"run {outcome.run_id}: status={outcome.run_status} gate={outcome.gate_decision} passed={outcome.passed}")
        if outcome.critical_failures:
            print(f"  critical failures: {', '.join(outcome.critical_failures)}")
        print(f"  {outcome.reason}")

    return 0 if outcome.passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
