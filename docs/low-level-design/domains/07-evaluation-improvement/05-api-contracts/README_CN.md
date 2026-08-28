# 05 API Contracts

## Dataset API

```http
POST /evaluation/datasets
POST /evaluation/datasets/{datasetId}/cases
POST /evaluation/datasets/{datasetId}/publish
GET  /evaluation/datasets/{datasetId}
GET  /evaluation/datasets?domain=IDENTITY_ACCESS&status=PUBLISHED
```

## Run API

```http
POST /evaluation/runs
GET  /evaluation/runs/{runId}
GET  /evaluation/runs/{runId}/scores
POST /evaluation/runs/{runId}/cancel
```

创建 run 请求：

```json
{
  "runKey": "ci-main-20260826-001",
  "datasetId": "identity-mfa-golden-dataset",
  "datasetVersion": "2026.08.1",
  "targetVersion": "agent-runtime:2026.08.26-rc1",
  "baselineVersion": "agent-runtime:2026.08.20",
  "triggeredBy": "ci",
  "gatePolicy": "mvp-release-gate-v1"
}
```

## Report API

```http
GET /evaluation/reports/{reportId}
GET /evaluation/runs/{runId}/regression-report
```

## Candidate API

```http
POST /evaluation/improvement-candidates
GET  /evaluation/improvement-candidates/{candidateId}
POST /evaluation/improvement-candidates/{candidateId}/benchmark
POST /evaluation/improvement-candidates/{candidateId}/request-approval
POST /evaluation/improvement-candidates/{candidateId}/start-canary
POST /evaluation/improvement-candidates/{candidateId}/rollback
```

## 管理 API

```http
GET /evaluation/audit
GET /evaluation/gates/{gatePolicy}
PUT /evaluation/gates/{gatePolicy}
GET /evaluation/graders
```

所有写 API 必须要求 01 提供的 service identity 或 evaluator/admin role，并写入 audit。

