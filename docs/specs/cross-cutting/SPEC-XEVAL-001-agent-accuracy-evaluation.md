# SPEC-XEVAL-001 — how to measure the agent's accuracy

Status: implemented 2026-09-10
Owner: platform / cross-cutting
Related: domain 07 (evaluation-improvement, SPEC-EI-001~036 — all built), [[langsmith-and-tracing-wired]], SPEC-XREL-001

## 1. What "accuracy" means here

`evaluation-improvement-service` runs a **published dataset** of test cases through the
**live agent** and scores each case with deterministic graders against that case's
`ground_truth`. Two datasets exist in the local stack:

| dataset id | name | cases | use |
|---|---|---|---|
| `10000000-…-0002` | OpsMind IT Support Routing | 6 | fast deterministic CI gate (static reasoning) |
| `10000000-…-0003` | OpsMind IT Support Routing **(extended)** | 18 (6 per class) | nightly real-LLM measurement (one miss = ±5.5%, not ±16.7%) |

Both carry the same shape of `ground_truth` (migrations `b5e1c8f37a20` / `c9f4a1b7e230`):

| case | user request | ground truth |
|---|---|---|
| `pw-reset-forgot`, `account-locked-out` | "I forgot my password" / "my account is locked" | `SELF_SERVICE_ACTION` → `AWAITING_USER_CONFIRMATION`, tool `send_password_reset` |
| `hardware-cracked-screen`, `hardware-wont-power-on` | "my screen is cracked" / "won't turn on" | `ESCALATED_TO_HUMAN` → `ESCALATED` |
| `vpn-connect-howto`, `printer-not-printing-howto` | "how do I connect to the VPN" / "my document won't print" | `INFORMATION_PROVIDED` → `RESPONDED` |

For each case eval-improvement calls agent-runtime's
`POST /agent-runtime/evaluation/execute-case` with only the redacted user request
(never the answer key), gets back `{finalState, classification, toolCalls, costTokens,
promptTokens, completionTokens, latencyMs, verificationPassed, ...}`, then scores:

| dimension | grader |
|---|---|
| **CLASSIFICATION_ACCURACY** | exact match: `ground_truth["classification"] == result.classification` |
| **RESOLUTION_SUCCESS** | `ground_truth["finalState"] == result.final_state` **and** `result.verification_passed` |
| **TOOL_SELECTION** | forbidden-tool overlap → 0; else `|called ∩ allowed| / |called|` |
| **POLICY_COMPLIANCE** | zero policy violations / forbidden-tool calls / unauthorized memory reads |
| **HANDOFF_COMPLETENESS** | quality-only; `UNSCORED` unless the dataset carries handoff ground truth (this one doesn't) |

The run-level "accuracy" for a dimension is the **mean of the case scores** for that
dimension (excluding `UNSCORED`).

## 2. The release gate

`mvp-release-gate-v1` (seeded by migration `06670df0f457`) gates on:

```
CLASSIFICATION_ACCURACY >= 0.90
TOOL_SELECTION          >= 0.95
every CRITICAL case scored
max policy violations / forbidden-tool calls / unauthorized memory reads = 0
```

`evaluate-gate` returns `PASSED` / `FAILED` / (for an incomplete run) no decision.

## 3. What the agent's answers depend on

The agent's response comes from whatever `CONVERSATION_REASONING_MODE` agent-runtime
runs:

- **`static`** — deterministic keyword routing. Free, reproducible. Use this to gate CI
  on *routing-logic* regressions (a code change that breaks classification shows up as
  a score drop).
- **`openai` / `anthropic`** — the real model. A few cents per run, **non-deterministic**
  (two runs of the same dataset can score differently, and a slow/failed model call can
  leave the run `PARTIAL`). This is the *true* accuracy number — track its trend in
  LangSmith, don't hard-gate a PR on it.

Every run is pushed to a LangSmith project (`LANGSMITH_MODE=sdk`): per-case scores +
prompt/completion token split. `GET /evaluation/runs/{runId}/langsmith-link` returns
the `experiment_ref`.

## 4. How to run it

### One command (`evaluation-ci-gate` CLI, drives the whole pipeline)

```bash
docker exec opsmind-evaluation-improvement-service evaluation-ci-gate \
  --run-key "$(uuidgen)" \
  --dataset-id 10000000-0000-0000-0000-000000000002 \
  --target-version "agent-runtime@$(git rev-parse --short HEAD)" \
  --grader-bundle-version grader-bundle-v1 \
  --policy-version policy-v1 \
  --gate-policy mvp-release-gate-v1 \
  --json
# -> {"runId": "...", "runStatus": "PASSED", "gateDecision": "PASSED", "passed": true}
# exit 0 if the gate passed, non-zero otherwise.
```

Then pull the numbers:

```bash
curl -s localhost:8011/evaluation/runs/<runId>/scores \
  -H 'X-Actor-Id: me' -H 'X-Actor-Role: create_run' | python3 -m json.tool
```

### The wrapper — `scripts/agent-accuracy-eval.sh`

Does exactly that and prints a readable table: per-dimension mean + `passed n/m`, **the
specific cases the agent got wrong** (with the grader's failure detail), the gate
decision, and the LangSmith link. Exit non-zero iff the gate failed.

```bash
scripts/agent-accuracy-eval.sh                       # default dataset
scripts/agent-accuracy-eval.sh <other-dataset-uuid>
REPORT_ONLY=1 scripts/agent-accuracy-eval.sh         # never exit non-zero (CI trend mode)
```

Env: `EVAL_TARGET_VERSION`, `EVAL_GATE_POLICY`, `EVAL_DATASET_ID`, `EVAL_URL`,
`EVAL_CONTAINER`.

### Step-by-step over REST (for a dashboard / debugging one case)

```
POST /evaluation/runs                        {run_key, dataset_id, target_version, gate_policy, grader_bundle_version, policy_version, triggered_by, correlation_id}
POST /evaluation/runs/{rid}/cases/{cid}/execute   (for every case — hits agent-runtime live)
POST /evaluation/runs/{rid}/cases/{cid}/score?correlation_id=...   (for every case)
POST /evaluation/runs/{rid}/finalize-scoring?correlation_id=...
POST /evaluation/runs/{rid}/compare?correlation_id=...
POST /evaluation/runs/{rid}/evaluate-gate?gate_policy=mvp-release-gate-v1&correlation_id=...
GET  /evaluation/runs/{rid}/scores            -> per-case per-dimension scores
```
Ordering matters: **execute every case, then score every case, then finalize** — the
first `score` moves the run `RUNNING → SCORING` and blocks further `execute`.
All eval endpoints authenticate with `X-Actor-Id` + `X-Actor-Role` headers (role
`create_run` for the run/score/gate calls).

## 5. CI

Two jobs, deliberately different:

- **`frontend-e2e.yml` → "Agent accuracy evaluation"** — runs after the smokes,
  `REPORT_ONLY=1 scripts/agent-accuracy-eval.sh`, against the **6-case** set with
  `CONVERSATION_REASONING_MODE=static`. Deterministic, free, every nightly + on-demand.
  `REPORT_ONLY=1` = always prints the table, never blocks the build. Flip it off to make
  the `mvp-release-gate-v1` binding on the routing *logic*.

- **`agent-accuracy-nightly.yml`** (new) — `schedule` 06:00 UTC + `workflow_dispatch`.
  Writes a real `.env` (`CONVERSATION_REASONING_MODE=openai`, `LANGSMITH_MODE=sdk`, keys
  from repo secrets `OPENAI_API_KEY` / `LANGSMITH_API_KEY`), brings the stack up, seeds
  the knowledge base, and runs `scripts/agent-accuracy-eval.sh` **N times** (default 2,
  `workflow_dispatch` input) against the **18-case extended** set. Every run is pushed to
  its own LangSmith project (`opsmind-eval-OpsMind IT Support Routing (extended)-v1-<runId>`)
  with per-case `llm` runs + per-dimension feedback + prompt/completion token counts —
  so the *real* accuracy accumulates a trend there. The job **never fails on a low
  score** (the real-model number is a distribution); it uploads the raw scores JSON as
  an artifact and writes a summary table to the job summary. Skips itself if
  `OPENAI_API_KEY` is unset.

Repo secrets the nightly needs: **`OPENAI_API_KEY`** (required — the model the agent
reasons with), **`LANGSMITH_API_KEY`** (required — where the scores go),
`ANTHROPIC_API_KEY` (optional).

## 6. Files

New:

```
docs/specs/cross-cutting/SPEC-XEVAL-001-agent-accuracy-evaluation.md   (this doc)
scripts/agent-accuracy-eval.sh
.github/workflows/agent-accuracy-nightly.yml
services/evaluation-improvement-service/migrations/versions/c9f4a1b7e230_seed_extended_routing_eval_dataset.py
```

Modified:

```
.github/workflows/frontend-e2e.yml   (+ "Agent accuracy evaluation" step)
```

No product-code change — the evaluation pipeline (domain 07) and the
`evaluation-ci-gate` CLI were already built; this makes running it a one-liner, adds a
statistically meaningful dataset, and puts the number in CI + LangSmith.

## 7. Observed (live, 2026-09-10)

Real OpenAI-backed agent:

- **6-case set, repeated** — CLASSIFICATION_ACCURACY swung 0.83 ↔ 1.00 across runs; the
  recurring miss is `printer-not-printing-howto` (agent over-escalates it,
  ESCALATED_TO_HUMAN vs ground-truth INFORMATION_PROVIDED), and a slow model call
  occasionally leaves a run `PARTIAL`.
- **18-case extended set, one run** — CLASSIFICATION_ACCURACY 1.00 (18/18),
  RESOLUTION_SUCCESS 1.00, TOOL_SELECTION 1.00, POLICY_COMPLIANCE 1.00; gate **PASSED**.
  Pushed to LangSmith: 18 per-case `llm` runs, feedback averages
  `{classification_accuracy: 1.0, resolution_success: 1.0, tool_selection: 1.0,
  policy_compliance: 1.0, handoff_completeness: 0.0}`, total tokens prompt 10,545 /
  completion 10,835.

The real-model number is a distribution, not a point — the deterministic CI gate runs
on `static`, and the real number's trend lives in LangSmith.
