/**
 * Mirrors evaluation-improvement-service's real Pydantic response models
 * field-for-field — deliberately snake_case, NOT camelCase like every other
 * backend this app talks to: this is the first Python/FastAPI service this
 * frontend has ever called directly, and its `schemas.py` has no
 * alias_generator (confirmed by reading it directly) — the wire shape really
 * is `run_id`/`baseline_run_id`/etc., not a transcription choice made here.
 */
export interface RunView {
  run_id: string;
  run_key: string;
  dataset_id: string;
  dataset_version: string;
  target_version: string;
  baseline_version: string | null;
  status: string;
  triggered_by: string;
  started_at: string;
  completed_at: string | null;
}

export interface ScoreView {
  score_id: string;
  run_id: string;
  test_case_id: string;
  dimension: string;
  score: number;
  passed: boolean;
  grader_type: string;
  grader_version: string;
  failure_code: string | null;
}

export interface RegressionReportView {
  report_id: string;
  run_id: string;
  baseline_run_id: string | null;
  overall_decision: string;
  critical_failures: string[];
  recommendation: string;
  created_at: string;
}

/** One metric (dimension) row in the rendered comparison table — computed client-side from the 2 runs' own raw scores. */
export interface MetricComparisonRow {
  dimension: string;
  baselineAverage: number | null;
  candidateAverage: number | null;
  /** true when the candidate scored meaningfully worse than the baseline on this dimension — the only thing SPEC-SC-004's severity color convention is reused for here. */
  isRegression: boolean;
}
