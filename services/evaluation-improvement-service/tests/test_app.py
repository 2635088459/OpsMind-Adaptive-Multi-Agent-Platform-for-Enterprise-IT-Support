"""End-to-end smoke test: constructs the real FastAPI app (real routers, real
container, SPEC-EI-001's own in-memory adapters) and drives the full dataset -> run ->
pipeline -> candidate -> canary walkthrough over real HTTP, mirroring
memory-knowledge-service's own tests/test_app.py precedent.
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from evaluationimprovement.main import create_app


@pytest.fixture()
def client() -> TestClient:
    return TestClient(create_app())


def _headers(role: str, actor_id: str = "actor-1") -> dict[str, str]:
    return {"X-Actor-Id": actor_id, "X-Actor-Role": role}


@pytest.mark.unit
def test_health_check(client: TestClient) -> None:
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "UP"}


@pytest.mark.unit
def test_full_evaluation_and_improvement_walkthrough(client: TestClient) -> None:
    # 1. Create + populate + publish a dataset.
    dataset_response = client.post(
        "/evaluation/datasets",
        json={
            "name": "identity-mfa-golden", "version": "2026.08.1", "domain": "IDENTITY_ACCESS", "scenario_tags": ["mfa"],
            "created_by": "author-1", "correlation_id": "corr-1",
        },
        headers=_headers("EVALUATION_AUTHOR", "author-1"),
    )
    assert dataset_response.status_code == 201, dataset_response.text
    dataset_id = dataset_response.json()["dataset_id"]

    cases_response = client.post(
        f"/evaluation/datasets/{dataset_id}/cases",
        json={
            "cases": [{
                "case_key": "duo-enrollment-expired", "scenario": "Duo enrollment expired",
                "ground_truth": {"classification": "MFA_ENROLLMENT_EXPIRED"}, "criticality": "CRITICAL",
            }],
            "correlation_id": "corr-1",
        },
        headers=_headers("EVALUATION_AUTHOR", "author-1"),
    )
    assert cases_response.status_code == 200, cases_response.text
    test_case_id = cases_response.json()[0]["test_case_id"]

    submit_review_response = client.post(
        f"/evaluation/datasets/{dataset_id}/submit-review", json={"correlation_id": "corr-1"},
        headers=_headers("EVALUATION_AUTHOR", "author-1"),
    )
    assert submit_review_response.status_code == 200, submit_review_response.text
    assert submit_review_response.json()["status"] == "REVIEWING"

    publish_response = client.post(
        f"/evaluation/datasets/{dataset_id}/publish", json={"published_by": "reviewer-1", "correlation_id": "corr-1"},
        headers=_headers("EVALUATION_REVIEWER", "reviewer-1"),
    )
    assert publish_response.status_code == 200, publish_response.text
    assert publish_response.json()["status"] == "PUBLISHED"

    # 2. Create and drive a benchmark run through to PASSED.
    run_response = client.post(
        "/evaluation/runs",
        json={
            "run_key": "http-e2e-001", "dataset_id": dataset_id, "target_version": "agent-runtime:2026.08.26-rc1",
            "gate_policy": "mvp-release-gate-v1", "triggered_by": "ci", "correlation_id": "corr-1",
        },
        headers=_headers("EVALUATION_ADMIN", "admin-1"),
    )
    assert run_response.status_code == 201, run_response.text
    run_id = run_response.json()["run_id"]

    execute_response = client.post(
        f"/evaluation/runs/{run_id}/cases/{test_case_id}/execute", params={"correlation_id": "corr-1"},
        headers=_headers("EVALUATION_ADMIN", "admin-1"),
    )
    assert execute_response.status_code == 202, execute_response.text

    score_response = client.post(
        f"/evaluation/runs/{run_id}/cases/{test_case_id}/score", params={"run_generation": 1, "correlation_id": "corr-1"},
        headers=_headers("EVALUATION_ADMIN", "admin-1"),
    )
    assert score_response.status_code == 200, score_response.text
    assert score_response.json()[0]["passed"] is True

    finalize_response = client.post(
        f"/evaluation/runs/{run_id}/finalize-scoring", params={"correlation_id": "corr-1"},
        headers=_headers("EVALUATION_ADMIN", "admin-1"),
    )
    assert finalize_response.status_code == 200, finalize_response.text
    assert finalize_response.json()["status"] == "COMPARING"

    compare_response = client.post(
        f"/evaluation/runs/{run_id}/compare", params={"correlation_id": "corr-1"}, headers=_headers("EVALUATION_ADMIN", "admin-1"),
    )
    assert compare_response.status_code == 200, compare_response.text
    assert compare_response.json()["overall_decision"] == "PASSED"

    gate_response = client.post(
        f"/evaluation/runs/{run_id}/evaluate-gate", params={"gate_policy": "mvp-release-gate-v1", "correlation_id": "corr-1"},
        headers=_headers("EVALUATION_ADMIN", "admin-1"),
    )
    assert gate_response.status_code == 200, gate_response.text
    assert gate_response.json()["overall_decision"] == "PASSED"

    final_run = client.get(f"/evaluation/runs/{run_id}")
    assert final_run.json()["status"] == "PASSED"

    # 3. Improvement candidate lifecycle through Canary to promotion.
    candidate_response = client.post(
        "/evaluation/improvement-candidates",
        json={
            "candidate_type": "PROMPT_CHANGE", "source_run_id": run_id, "target_component": "identity-agent-prompt",
            "proposed_change": {"promptDiff": "clarify MFA enrollment expiry wording"}, "risk_level": "MEDIUM",
            "created_by": "author-1", "correlation_id": "corr-1", "idempotency_key": "candidate-e2e-1",
        },
        headers=_headers("EVALUATION_AUTHOR", "author-1"),
    )
    assert candidate_response.status_code == 201, candidate_response.text
    candidate_id = candidate_response.json()["candidate_id"]

    benchmark_response = client.post(
        f"/evaluation/improvement-candidates/{candidate_id}/benchmark", json={"passed": True, "correlation_id": "corr-1"},
        headers=_headers("EVALUATION_AUTHOR", "author-1"),
    )
    assert benchmark_response.status_code == 200
    assert benchmark_response.json()["status"] == "BENCHMARKING"

    request_approval_response = client.post(
        f"/evaluation/improvement-candidates/{candidate_id}/request-approval", json={"correlation_id": "corr-1"},
        headers=_headers("EVALUATION_AUTHOR", "author-1"),
    )
    assert request_approval_response.status_code == 200
    assert request_approval_response.json()["status"] == "PENDING_APPROVAL"
    assert request_approval_response.json()["approval_request_id"] is not None

    approve_response = client.post(
        f"/evaluation/improvement-candidates/{candidate_id}/approve",
        json={"approved_by": "approver-1", "correlation_id": "corr-1"}, headers=_headers("RELEASE_APPROVER", "approver-1"),
    )
    assert approve_response.status_code == 200, approve_response.text
    assert approve_response.json()["status"] == "APPROVED"

    canary_response = client.post(
        f"/evaluation/improvement-candidates/{candidate_id}/start-canary",
        json={
            "plan_version": "v1", "stages": [{"traffic_percent": 5.0, "min_duration_minutes": 30, "rollback_error_rate_threshold": 0.05}],
            "correlation_id": "corr-1", "idempotency_key": "canary-e2e-1",
        },
        headers=_headers("EVALUATION_ADMIN", "admin-1"),
    )
    assert canary_response.status_code == 200, canary_response.text
    assert canary_response.json()["canary_status"] == "ACTIVE"


@pytest.mark.unit
def test_unauthorized_role_is_rejected(client: TestClient) -> None:
    """14-testing-strategy §"Security Tests": "service identity 缺失时写 API 被拒绝"."""
    response = client.post(
        "/evaluation/datasets",
        json={"name": "x", "version": "1", "domain": "IDENTITY_ACCESS", "created_by": "a", "correlation_id": "corr-1"},
        headers=_headers("EVALUATION_VIEWER", "viewer-1"),
    )
    assert response.status_code == 403


@pytest.mark.unit
def test_missing_actor_headers_is_rejected(client: TestClient) -> None:
    """interfaces.errors normalizes every RequestValidationError (including a missing
    required X-Actor-Id/X-Actor-Role header) to 400, not FastAPI's own default 422.
    """
    response = client.post(
        "/evaluation/datasets",
        json={"name": "x", "version": "1", "domain": "IDENTITY_ACCESS", "created_by": "a", "correlation_id": "corr-1"},
    )
    assert response.status_code == 400


@pytest.mark.unit
def test_admin_gate_policy_and_grader_catalog(client: TestClient) -> None:
    gate_response = client.get("/evaluation/gates/mvp-release-gate-v1")
    assert gate_response.status_code == 200
    assert gate_response.json()["gate_policy"] == "mvp-release-gate-v1"

    graders_response = client.get("/evaluation/graders")
    assert graders_response.status_code == 200
    assert len(graders_response.json()) >= 1


@pytest.mark.unit
def test_dataset_versioning_and_lifecycle_over_http(client: TestClient) -> None:
    """SPEC-EI-004: new-version-with-lineage, then deprecate/archive, all over real
    HTTP.
    """
    dataset_response = client.post(
        "/evaluation/datasets",
        json={
            "name": "okta-session-golden", "version": "1", "domain": "IDENTITY_ACCESS", "created_by": "author-1",
            "correlation_id": "corr-1",
        },
        headers=_headers("EVALUATION_AUTHOR", "author-1"),
    )
    dataset_id = dataset_response.json()["dataset_id"]
    client.post(
        f"/evaluation/datasets/{dataset_id}/cases",
        json={"cases": [{"case_key": "k1", "scenario": "s", "ground_truth": {"classification": "X"}}], "correlation_id": "corr-1"},
        headers=_headers("EVALUATION_AUTHOR", "author-1"),
    )
    client.post(
        f"/evaluation/datasets/{dataset_id}/submit-review", json={"correlation_id": "corr-1"},
        headers=_headers("EVALUATION_AUTHOR", "author-1"),
    )
    client.post(
        f"/evaluation/datasets/{dataset_id}/publish", json={"published_by": "reviewer-1", "correlation_id": "corr-1"},
        headers=_headers("EVALUATION_REVIEWER", "reviewer-1"),
    )

    version_response = client.post(
        f"/evaluation/datasets/{dataset_id}/versions",
        json={"new_version": "2", "created_by": "author-2", "correlation_id": "corr-1"},
        headers=_headers("EVALUATION_AUTHOR", "author-2"),
    )
    assert version_response.status_code == 201, version_response.text
    assert version_response.json()["status"] == "DRAFT"
    assert version_response.json()["case_count"] == 1

    lineage_response = client.get(
        "/evaluation/datasets", params={"name": "okta-session-golden"}, headers=_headers("EVALUATION_VIEWER", "viewer-1"),
    )
    assert lineage_response.status_code == 200
    assert [d["version"] for d in lineage_response.json()] == ["1", "2"]

    deprecate_response = client.post(
        f"/evaluation/datasets/{dataset_id}/deprecate", json={"correlation_id": "corr-1"}, headers=_headers("EVALUATION_ADMIN", "admin-1"),
    )
    assert deprecate_response.status_code == 200
    assert deprecate_response.json()["status"] == "DEPRECATED"

    archive_response = client.post(
        f"/evaluation/datasets/{dataset_id}/archive", json={"correlation_id": "corr-1"}, headers=_headers("EVALUATION_ADMIN", "admin-1"),
    )
    assert archive_response.status_code == 200
    assert archive_response.json()["status"] == "ARCHIVED"


@pytest.mark.unit
def test_dataset_reads_require_an_authenticated_evaluation_role(client: TestClient) -> None:
    """SPEC-EI-008 / 11-security: reads were previously wide open to anyone — now they
    require the same caller-asserted identity headers as writes, just any known
    evaluation role rather than a specific one.
    """
    dataset_response = client.post(
        "/evaluation/datasets",
        json={"name": "read-gate-dataset", "version": "1", "domain": "IDENTITY_ACCESS", "created_by": "author-1", "correlation_id": "corr-1"},
        headers=_headers("EVALUATION_AUTHOR", "author-1"),
    )
    dataset_id = dataset_response.json()["dataset_id"]

    # No identity headers at all -> 400 (same normalization as the write-side test).
    assert client.get(f"/evaluation/datasets/{dataset_id}").status_code == 400

    # A real header pair, but a role this domain never issues -> 403, not silently allowed.
    assert client.get(f"/evaluation/datasets/{dataset_id}", headers=_headers("NOT_A_REAL_ROLE", "someone")).status_code == 403

    # Any genuine evaluation role, including the read-only floor, is let through.
    ok_response = client.get(f"/evaluation/datasets/{dataset_id}", headers=_headers("EVALUATION_VIEWER", "viewer-1"))
    assert ok_response.status_code == 200
    assert ok_response.json()["dataset_id"] == dataset_id


@pytest.mark.unit
def test_dataset_tenant_scope_isolates_reads_over_http(client: TestClient) -> None:
    """SPEC-EI-008 / 11-security: a dataset created under one `X-Tenant-Id` reads back
    as 404 for a caller asserting a different tenant.
    """
    create_headers = {**_headers("EVALUATION_AUTHOR", "author-1"), "X-Tenant-Id": "tenant-http-a"}
    dataset_response = client.post(
        "/evaluation/datasets",
        json={"name": "tenant-http-dataset", "version": "1", "domain": "IDENTITY_ACCESS", "created_by": "author-1", "correlation_id": "corr-1"},
        headers=create_headers,
    )
    assert dataset_response.status_code == 201, dataset_response.text
    dataset_id = dataset_response.json()["dataset_id"]
    assert dataset_response.json()["tenant_id"] == "tenant-http-a"

    same_tenant_headers = {**_headers("EVALUATION_VIEWER", "viewer-1"), "X-Tenant-Id": "tenant-http-a"}
    assert client.get(f"/evaluation/datasets/{dataset_id}", headers=same_tenant_headers).status_code == 200

    other_tenant_headers = {**_headers("EVALUATION_VIEWER", "viewer-1"), "X-Tenant-Id": "tenant-http-b"}
    assert client.get(f"/evaluation/datasets/{dataset_id}", headers=other_tenant_headers).status_code == 404


@pytest.mark.unit
def test_skip_case_over_http_marks_the_run_partial(client: TestClient) -> None:
    """SPEC-EI-009: the skip endpoint is a thin pass-through onto
    ExecuteCaseService.skip_case() — a real HTTP smoke test that the router/mapper
    wiring itself is correct, mirroring execute/score/finalize-scoring above."""
    dataset_response = client.post(
        "/evaluation/datasets",
        json={"name": "http-skip-dataset", "version": "1", "domain": "IDENTITY_ACCESS", "created_by": "author-1", "correlation_id": "corr-1"},
        headers=_headers("EVALUATION_AUTHOR", "author-1"),
    )
    dataset_id = dataset_response.json()["dataset_id"]
    cases_response = client.post(
        f"/evaluation/datasets/{dataset_id}/cases",
        json={"cases": [{"case_key": "k1", "scenario": "s", "ground_truth": {"classification": "X"}}], "correlation_id": "corr-1"},
        headers=_headers("EVALUATION_AUTHOR", "author-1"),
    )
    test_case_id = cases_response.json()[0]["test_case_id"]
    client.post(f"/evaluation/datasets/{dataset_id}/submit-review", json={"correlation_id": "corr-1"}, headers=_headers("EVALUATION_AUTHOR", "author-1"))
    client.post(
        f"/evaluation/datasets/{dataset_id}/publish", json={"published_by": "reviewer-1", "correlation_id": "corr-1"},
        headers=_headers("EVALUATION_REVIEWER", "reviewer-1"),
    )
    run_response = client.post(
        "/evaluation/runs",
        json={
            "run_key": "http-skip-run-001", "dataset_id": dataset_id, "target_version": "agent-runtime:rc1",
            "gate_policy": "mvp-release-gate-v1", "triggered_by": "ci", "correlation_id": "corr-1",
        },
        headers=_headers("EVALUATION_ADMIN", "admin-1"),
    )
    run_id = run_response.json()["run_id"]

    skip_response = client.post(
        f"/evaluation/runs/{run_id}/cases/{test_case_id}/skip", json={"reason": "known flaky case", "correlation_id": "corr-1"},
        headers=_headers("EVALUATION_ADMIN", "admin-1"),
    )
    assert skip_response.status_code == 202, skip_response.text
    assert skip_response.json()["status"] == "skipped"

    finalize_response = client.post(
        f"/evaluation/runs/{run_id}/finalize-scoring", params={"correlation_id": "corr-1"},
        headers=_headers("EVALUATION_ADMIN", "admin-1"),
    )
    assert finalize_response.status_code == 200, finalize_response.text
    assert finalize_response.json()["status"] == "PARTIAL"


@pytest.mark.unit
def test_run_cancel_is_idempotent_and_list_runs_over_http(client: TestClient) -> None:
    """SPEC-EI-010: real HTTP smoke test for both new capabilities — a resubmitted
    cancel returns 200 instead of erroring, and GET /evaluation/runs?dataset_id=
    exposes "状态可见性" over the real router/mapper wiring.
    """
    dataset_response = client.post(
        "/evaluation/datasets",
        json={"name": "http-run-visibility-dataset", "version": "1", "domain": "IDENTITY_ACCESS", "created_by": "author-1", "correlation_id": "corr-1"},
        headers=_headers("EVALUATION_AUTHOR", "author-1"),
    )
    dataset_id = dataset_response.json()["dataset_id"]
    client.post(
        f"/evaluation/datasets/{dataset_id}/cases",
        json={"cases": [{"case_key": "k1", "scenario": "s", "ground_truth": {"classification": "X"}}], "correlation_id": "corr-1"},
        headers=_headers("EVALUATION_AUTHOR", "author-1"),
    )
    client.post(f"/evaluation/datasets/{dataset_id}/submit-review", json={"correlation_id": "corr-1"}, headers=_headers("EVALUATION_AUTHOR", "author-1"))
    client.post(
        f"/evaluation/datasets/{dataset_id}/publish", json={"published_by": "reviewer-1", "correlation_id": "corr-1"},
        headers=_headers("EVALUATION_REVIEWER", "reviewer-1"),
    )
    run_response = client.post(
        "/evaluation/runs",
        json={
            "run_key": "http-cancel-idempotent-001", "dataset_id": dataset_id, "target_version": "agent-runtime:rc1",
            "gate_policy": "mvp-release-gate-v1", "triggered_by": "ci", "correlation_id": "corr-1",
        },
        headers=_headers("EVALUATION_ADMIN", "admin-1"),
    )
    run_id = run_response.json()["run_id"]

    first_cancel = client.post(f"/evaluation/runs/{run_id}/cancel", json={"reason": "stopping", "correlation_id": "corr-1"}, headers=_headers("EVALUATION_ADMIN", "admin-1"))
    assert first_cancel.status_code == 200, first_cancel.text
    assert first_cancel.json()["status"] == "CANCELLED"

    second_cancel = client.post(f"/evaluation/runs/{run_id}/cancel", json={"reason": "stopping again", "correlation_id": "corr-2"}, headers=_headers("EVALUATION_ADMIN", "admin-1"))
    assert second_cancel.status_code == 200, second_cancel.text
    assert second_cancel.json()["status"] == "CANCELLED"

    list_response = client.get("/evaluation/runs", params={"dataset_id": dataset_id})
    assert list_response.status_code == 200, list_response.text
    assert [r["run_id"] for r in list_response.json()] == [run_id]
