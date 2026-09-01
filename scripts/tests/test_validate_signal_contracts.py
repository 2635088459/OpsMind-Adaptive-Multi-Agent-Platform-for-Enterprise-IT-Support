"""Self-tests for scripts/validate-signal-contracts.py (SPEC-OP-004).

Run: uv run --with pyyaml python -m unittest discover -s scripts/tests
"""
from __future__ import annotations

import importlib.util
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "scripts" / "validate-signal-contracts.py"
OBS = REPO / "infrastructure" / "observability"

_spec = importlib.util.spec_from_file_location("vsc", SCRIPT)
vsc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(vsc)  # type: ignore[union-attr]


TP = {
    "baggage": {
        "max_total_bytes": 1024,
        "max_entries": 8,
        "allowed_keys": [
            {"key": "correlation_id", "value_pattern": r"^[A-Z]{2,5}-\d{1,10}$"},
            {"key": "request.priority", "value_pattern": r"^(low|normal|high|critical)$"},
        ],
    }
}


class PropagationUnitTests(unittest.TestCase):
    def test_parse_baggage(self) -> None:
        self.assertEqual(vsc._parse_baggage("a=1, b=2"), [("a", "1"), ("b", "2")])

    def test_conformant_http(self) -> None:
        ok, _ = vsc._propagation_conformant(TP, {
            "traceparent": "00-" + "a" * 32 + "-" + "b" * 16 + "-01",
            "baggage": "correlation_id=INC-2048,request.priority=high",
        })
        self.assertTrue(ok)

    def test_b3_rejected(self) -> None:
        ok, reasons = vsc._propagation_conformant(TP, {"x-b3-traceid": "abc"})
        self.assertFalse(ok)
        self.assertTrue(any("forbidden propagator" in r for r in reasons))
        self.assertTrue(any("traceparent" in r for r in reasons))

    def test_forbidden_baggage_key(self) -> None:
        ok, reasons = vsc._propagation_conformant(TP, {
            "traceparent": "00-" + "a" * 32 + "-" + "b" * 16 + "-01",
            "baggage": "authorization=Bearer%20x,correlation_id=INC-1",
        })
        self.assertFalse(ok)
        self.assertTrue(any("not in allow-list" in r for r in reasons))


MN = {
    "naming": {
        "name_pattern": r"^[a-z][a-z0-9_]*[a-z0-9]$",
        "namespaces": ["http", "agent", "evaluation"],
        "allowed_suffixes": ["_seconds", "_total", "_ratio"],
    },
    "units": {"forbidden_substrings": ["_ms", "_percent"]},
    "namespaces": [
        {"namespace": "http", "allowed_labels": ["http_request_method", "outcome"],
         "forbidden_labels": ["ticket_id"], "max_label_keys": 6},
        {"namespace": "agent", "allowed_labels": ["agent_role", "model", "outcome"],
         "forbidden_labels": ["ticket_id", "run_id"], "max_label_keys": 8},
    ],
}


class MetricNamingUnitTests(unittest.TestCase):
    def test_conformant_counter(self) -> None:
        ok, _ = vsc._metric_conformant(MN, {
            "name": "agent_tool_calls_total", "type": "counter", "namespace": "agent",
            "labels": ["agent_role", "model", "outcome"]})
        self.assertTrue(ok)

    def test_bad_unit_rejected(self) -> None:
        ok, reasons = vsc._metric_conformant(MN, {
            "name": "http_server_request_duration_ms", "type": "histogram",
            "namespace": "http", "labels": []})
        self.assertFalse(ok)
        self.assertTrue(any("_ms" in r for r in reasons))

    def test_forbidden_label_rejected(self) -> None:
        ok, reasons = vsc._metric_conformant(MN, {
            "name": "agent_run_duration_seconds", "type": "histogram",
            "namespace": "agent", "labels": ["agent_role", "ticket_id"]})
        self.assertFalse(ok)
        self.assertTrue(any("forbidden" in r for r in reasons))

    def test_camelcase_rejected(self) -> None:
        ok, _ = vsc._metric_conformant(MN, {
            "name": "EvaluationScoreRatio", "type": "gauge",
            "namespace": "evaluation", "labels": []})
        self.assertFalse(ok)


SL = {
    "unspecified_severity_number": 0,
    "severity_map": [
        {"level": "info", "severity_text_prefix": "INFO", "severity_number_min": 9, "severity_number_max": 12},
        {"level": "error", "severity_text_prefix": "ERROR", "severity_number_min": 17, "severity_number_max": 20},
    ],
    "event_code": {"pattern": r"^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*){1,4}$", "attribute": "event.code", "required": False},
    "linkage": {"any_of": ["trace_id", "correlation_id"]},
    "multiline": {"one_event_per_record": True, "max_body_chars": 100, "truncation_attribute": "opsmind.log.truncated"},
}
SL_GOV = {
    "log_body_redaction": {
        "patterns": [
            {"name": "email", "pattern": r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"},
        ]
    }
}


class StructuredLogUnitTests(unittest.TestCase):
    def test_conformant_record(self) -> None:
        ok, _ = vsc._structured_log_conformant(SL, SL_GOV, {
            "severity_number": 9, "severity_text": "INFO", "body": "hello",
            "attributes": {"trace_id": "abc", "event.code": "ticket.created"}})
        self.assertTrue(ok)

    def test_unspecified_severity_rejected(self) -> None:
        ok, reasons = vsc._structured_log_conformant(SL, SL_GOV, {
            "severity_number": 0, "severity_text": "", "body": "x",
            "attributes": {"trace_id": "abc"}})
        self.assertFalse(ok)
        self.assertTrue(any("unspecified" in r for r in reasons))

    def test_missing_linkage_rejected(self) -> None:
        ok, reasons = vsc._structured_log_conformant(SL, SL_GOV, {
            "severity_number": 9, "severity_text": "INFO", "body": "x", "attributes": {}})
        self.assertFalse(ok)
        self.assertTrue(any("missing linkage" in r for r in reasons))

    def test_bad_event_code_rejected(self) -> None:
        ok, reasons = vsc._structured_log_conformant(SL, SL_GOV, {
            "severity_number": 17, "severity_text": "ERROR", "body": "x",
            "attributes": {"trace_id": "abc", "event.code": "PaymentFailed"}})
        self.assertFalse(ok)
        self.assertTrue(any("event.code" in r for r in reasons))

    def test_raw_email_in_body_rejected(self) -> None:
        ok, reasons = vsc._structured_log_conformant(SL, SL_GOV, {
            "severity_number": 9, "severity_text": "INFO", "body": "hi jane@example.com",
            "attributes": {"trace_id": "abc"}})
        self.assertFalse(ok)
        self.assertTrue(any("log_body_redaction" in r for r in reasons))

    def test_oversized_body_without_truncation_marker_rejected(self) -> None:
        ok, reasons = vsc._structured_log_conformant(SL, SL_GOV, {
            "severity_number": 9, "severity_text": "INFO", "body": "x" * 5,
            "_body_repeat_count": 30, "attributes": {"trace_id": "abc"}})
        self.assertFalse(ok)
        self.assertTrue(any("max_body_chars" in r for r in reasons))

    def test_oversized_body_with_truncation_marker_accepted(self) -> None:
        ok, _ = vsc._structured_log_conformant(SL, SL_GOV, {
            "severity_number": 9, "severity_text": "INFO", "body": "x" * 5,
            "_body_repeat_count": 30,
            "attributes": {"trace_id": "abc", "opsmind.log.truncated": "true"}})
        self.assertTrue(ok)


class UnitTests(unittest.TestCase):
    def test_conformant_helper(self) -> None:
        attrs = [
            {"key": "service.name", "level": "required", "value_pattern": "^[a-z-]+$"},
            {"key": "deployment.environment", "level": "required", "value_enum_ref": "environments"},
        ]
        enums = {"environments": ["local", "ci"], "namespaces": []}
        ok, _ = vsc._conformant(attrs, enums, {"service.name": "svc", "deployment.environment": "ci"})
        self.assertTrue(ok)
        bad, reasons = vsc._conformant(attrs, enums, {"service.name": "Svc", "deployment.environment": "prod"})
        self.assertFalse(bad)
        self.assertEqual(len(reasons), 2)

    def test_real_tree_passes(self) -> None:
        r = subprocess.run([sys.executable, str(SCRIPT)], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)


class EndToEndTests(unittest.TestCase):
    def _clone(self, tmp: str) -> Path:
        clone = Path(tmp) / "repo"
        (clone / "infrastructure").mkdir(parents=True)
        (clone / "scripts").mkdir()
        shutil.copytree(OBS, clone / "infrastructure" / "observability")
        shutil.copy(SCRIPT, clone / "scripts" / SCRIPT.name)
        return clone

    def _run(self, clone: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run([sys.executable, str(clone / "scripts" / SCRIPT.name)],
                              capture_output=True, text=True)

    def test_conformant_fixture_broken_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            clone = self._clone(tmp)
            fx = (clone / "infrastructure" / "observability" / "signals" / "fixtures"
                  / "resource-attributes" / "conformant-java.json")
            fx.write_text(fx.read_text(encoding="utf-8").replace(
                '{ "key": "service.namespace", "value": { "stringValue": "ticket-workflow" } },', ""),
                encoding="utf-8")
            r = self._run(clone)
            self.assertEqual(r.returncode, 1)
            self.assertIn("expected conformant but failed", r.stdout)

    def test_required_set_desync_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            clone = self._clone(tmp)
            gov = (clone / "infrastructure" / "observability" / "governance"
                   / "telemetry-governance.yaml")
            gov.write_text(gov.read_text(encoding="utf-8").replace(
                '"telemetry.sdk.language"]', "]", 1), encoding="utf-8")
            r = self._run(clone)
            self.assertEqual(r.returncode, 1)
            self.assertIn("telemetry.sdk.language", r.stdout)
            self.assertIn("does not", r.stdout)

    def test_collector_unwired_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            clone = self._clone(tmp)
            cfg = (clone / "infrastructure" / "observability" / "collector" / "base" / "config.yaml")
            cfg.write_text(cfg.read_text(encoding="utf-8").replace(
                "transform/resource-contract, ", ""), encoding="utf-8")
            r = self._run(clone)
            self.assertEqual(r.returncode, 1)
            self.assertIn("resource-contract", r.stdout)

    def test_propagation_fixture_broken_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            clone = self._clone(tmp)
            fx = (clone / "infrastructure" / "observability" / "signals" / "fixtures"
                  / "trace-propagation" / "http-inbound-conformant.json")
            fx.write_text(fx.read_text(encoding="utf-8").replace(
                "request.priority=high", "user.email=jo%40x.com"), encoding="utf-8")
            r = self._run(clone)
            self.assertEqual(r.returncode, 1)
            self.assertIn("expected conformant but failed", r.stdout)

    def test_baggage_contract_unwired_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            clone = self._clone(tmp)
            cfg = (clone / "infrastructure" / "observability" / "collector" / "base" / "config.yaml")
            cfg.write_text(cfg.read_text(encoding="utf-8").replace(
                "transform/baggage-contract, ", ""), encoding="utf-8")
            r = self._run(clone)
            self.assertEqual(r.returncode, 1)
            self.assertIn("baggage-contract", r.stdout)

    def test_metric_fixture_broken_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            clone = self._clone(tmp)
            fx = (clone / "infrastructure" / "observability" / "signals" / "fixtures"
                  / "metric-naming" / "conformant-agent-counter.json")
            text = fx.read_text(encoding="utf-8")
            broken = text.replace('"labels": ["workflow_type"]', '"labels": ["workflow_type", "ticket_id"]')
            self.assertNotEqual(text, broken, "fixture replace did not match — update this test")
            fx.write_text(broken, encoding="utf-8")
            r = self._run(clone)
            self.assertEqual(r.returncode, 1)
            self.assertIn("expected conformant but failed", r.stdout)

    def test_metric_cardinality_unwired_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            clone = self._clone(tmp)
            cfg = (clone / "infrastructure" / "observability" / "collector" / "base" / "config.yaml")
            cfg.write_text(cfg.read_text(encoding="utf-8").replace(
                "transform/metric-cardinality, ", ""), encoding="utf-8")
            r = self._run(clone)
            self.assertEqual(r.returncode, 1)
            self.assertIn("metric-cardinality", r.stdout)

    def test_structured_log_fixture_broken_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            clone = self._clone(tmp)
            fx = (clone / "infrastructure" / "observability" / "signals" / "fixtures"
                  / "structured-log" / "conformant-info-trace-linkage.json")
            fx.write_text(fx.read_text(encoding="utf-8").replace(
                '"severity_number": 9,', '"severity_number": 0,'), encoding="utf-8")
            r = self._run(clone)
            self.assertEqual(r.returncode, 1)
            self.assertIn("expected conformant but failed", r.stdout)

    def test_log_schema_contract_unwired_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            clone = self._clone(tmp)
            cfg = (clone / "infrastructure" / "observability" / "collector" / "base" / "config.yaml")
            cfg.write_text(cfg.read_text(encoding="utf-8").replace(
                "transform/log-schema-contract, ", ""), encoding="utf-8")
            r = self._run(clone)
            self.assertEqual(r.returncode, 1)
            self.assertIn("log-schema-contract", r.stdout)

    def test_log_body_redaction_unwired_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            clone = self._clone(tmp)
            cfg = (clone / "infrastructure" / "observability" / "collector" / "base" / "config.yaml")
            cfg.write_text(cfg.read_text(encoding="utf-8").replace(
                "transform/log-body-redaction, ", ""), encoding="utf-8")
            r = self._run(clone)
            self.assertEqual(r.returncode, 1)
            self.assertIn("log-body-redaction", r.stdout)

    def test_structured_log_governance_event_code_desync_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            clone = self._clone(tmp)
            gov = (clone / "infrastructure" / "observability" / "governance"
                   / "telemetry-governance.yaml")
            gov.write_text(gov.read_text(encoding="utf-8").replace(
                '"log.level", "event.code"]', '"log.level"]', 1), encoding="utf-8")
            r = self._run(clone)
            self.assertEqual(r.returncode, 1)
            self.assertIn("event.code", r.stdout)

    def test_governance_namespace_desync_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            clone = self._clone(tmp)
            mn = (clone / "infrastructure" / "observability" / "signals" / "metric-naming.yaml")
            mn.write_text(mn.read_text(encoding="utf-8").replace(
                "max_series: 40000", "max_series: 99999", 1), encoding="utf-8")
            r = self._run(clone)
            self.assertEqual(r.returncode, 1)
            self.assertIn("max_series", r.stdout)


if __name__ == "__main__":
    unittest.main()
