"""Self-tests for scripts/validate-telemetry-governance.py (SPEC-OP-003).

Run: uv run --with pyyaml python -m unittest scripts.tests.test_validate_telemetry_governance
     (or: pip install pyyaml && python -m unittest discover -s scripts/tests)
"""
from __future__ import annotations

import datetime as dt
import importlib.util
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "scripts" / "validate-telemetry-governance.py"
OBS = REPO / "infrastructure" / "observability"

_spec = importlib.util.spec_from_file_location("vtg", SCRIPT)
vtg = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(vtg)  # type: ignore[union-attr]


class UnitTests(unittest.TestCase):
    def test_canonical_regex(self) -> None:
        r = vtg.canonical_regex([{"pattern": "authorization"}, {"pattern": "token"}])
        self.assertEqual(r, "(?i)(authorization|token)")

    def test_real_governance_file_matches_collector(self) -> None:
        import yaml
        doc = yaml.safe_load(vtg.GOV.read_text(encoding="utf-8"))
        canon = vtg.canonical_regex(doc["deny_fields"]).replace("\\.", "\\\\.")
        self.assertIn(canon, vtg.COLLECTOR.read_text(encoding="utf-8"))


class EndToEndTests(unittest.TestCase):
    def _clone(self, tmp: str) -> Path:
        clone = Path(tmp) / "repo"
        (clone / "infrastructure").mkdir(parents=True)
        (clone / "scripts").mkdir()
        shutil.copytree(OBS, clone / "infrastructure" / "observability")
        shutil.copy(SCRIPT, clone / "scripts" / SCRIPT.name)
        return clone

    def _run(self, script: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run([sys.executable, str(script)], capture_output=True, text=True)

    def _gov(self, clone: Path) -> Path:
        return clone / "infrastructure" / "observability" / "governance" / "telemetry-governance.yaml"

    def test_real_tree_passes(self) -> None:
        r = self._run(SCRIPT)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_expired_exception_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            clone = self._clone(tmp)
            g = self._gov(clone)
            past = (dt.date.today() - dt.timedelta(days=5)).isoformat()
            opened = (dt.date.today() - dt.timedelta(days=20)).isoformat()
            g.write_text(g.read_text(encoding="utf-8").replace(
                "exceptions: []",
                "exceptions:\n"
                "  - id: TGX-001\n"
                "    rule: allow_fields:metric_datapoint\n"
                "    scope: test\n"
                "    reason: test\n"
                "    owner: platform-observability\n"
                "    approved_by: platform-observability\n"
                f"    opened: {opened}\n"
                f"    expires: {past}\n"
                "    ticket: OPS-1\n",
            ), encoding="utf-8")
            r = self._run(clone / "scripts" / SCRIPT.name)
            self.assertEqual(r.returncode, 1)
            self.assertIn("EXPIRED", r.stdout)

    def test_desynced_collector_regex_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            clone = self._clone(tmp)
            cfg = clone / "infrastructure" / "observability" / "collector" / "base" / "config.yaml"
            cfg.write_text(cfg.read_text(encoding="utf-8").replace("authorization|", "xxx|"),
                           encoding="utf-8")
            r = self._run(clone / "scripts" / SCRIPT.name)
            self.assertEqual(r.returncode, 1)
            self.assertIn("diverged", r.stdout)

    def test_gutted_baseline_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            clone = self._clone(tmp)
            g = self._gov(clone)
            text = g.read_text(encoding="utf-8")
            text = text.replace('  - pattern: "token"\n    reason: "access / refresh / id token"\n', "")
            text = text.replace('  - pattern: "session[_-]?id"\n    reason: "session identifier"\n', "")
            g.write_text(text, encoding="utf-8")
            r = self._run(clone / "scripts" / SCRIPT.name)
            self.assertEqual(r.returncode, 1)
            self.assertIn("baseline concept", r.stdout)

    def test_log_body_redaction_gutted_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            clone = self._clone(tmp)
            g = self._gov(clone)
            text = g.read_text(encoding="utf-8")
            text = text.replace(
                '    - name: "email"\n'
                '      pattern: "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\\\.[A-Za-z]{2,}"\n'
                '      replacement: "[REDACTED_EMAIL]"\n'
                '      reason: "PII email address"\n', "")
            g.write_text(text, encoding="utf-8")
            r = self._run(clone / "scripts" / SCRIPT.name)
            self.assertEqual(r.returncode, 1)
            self.assertIn("baseline concept", r.stdout)
            self.assertIn("email", r.stdout)

    def test_log_body_redaction_collector_unwired_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            clone = self._clone(tmp)
            cfg = clone / "infrastructure" / "observability" / "collector" / "base" / "config.yaml"
            cfg.write_text(cfg.read_text(encoding="utf-8").replace(
                "transform/log-body-redaction, ", ""), encoding="utf-8")
            r = self._run(clone / "scripts" / SCRIPT.name)
            self.assertEqual(r.returncode, 1)
            self.assertIn("log-body-redaction", r.stdout)

    def test_trace_sampling_bad_threshold_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            clone = self._clone(tmp)
            g = self._gov(clone)
            g.write_text(g.read_text(encoding="utf-8").replace(
                "slow_trace_threshold_ms: 1000", "slow_trace_threshold_ms: -5"),
                encoding="utf-8")
            r = self._run(clone / "scripts" / SCRIPT.name)
            self.assertEqual(r.returncode, 1)
            self.assertIn("slow_trace_threshold_ms", r.stdout)

    def test_trace_sampling_collector_unwired_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            clone = self._clone(tmp)
            cfg = clone / "infrastructure" / "observability" / "collector" / "base" / "config.yaml"
            cfg.write_text(cfg.read_text(encoding="utf-8").replace(
                "transform/governance, tail_sampling, batch]",
                "transform/governance, batch]"), encoding="utf-8")
            r = self._run(clone / "scripts" / SCRIPT.name)
            self.assertEqual(r.returncode, 1)
            self.assertIn("tail_sampling", r.stdout)

    def test_denyfield_waiver_without_domain6_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            clone = self._clone(tmp)
            g = self._gov(clone)
            opened = dt.date.today().isoformat()
            expires = (dt.date.today() + dt.timedelta(days=30)).isoformat()
            g.write_text(g.read_text(encoding="utf-8").replace(
                "exceptions: []",
                "exceptions:\n"
                "  - id: TGX-009\n"
                "    rule: deny_fields:token\n"
                "    scope: test\n"
                "    reason: test\n"
                "    owner: agent-runtime\n"
                "    approved_by: platform-observability\n"
                f"    opened: {opened}\n"
                f"    expires: {expires}\n"
                "    ticket: OPS-9\n",
            ), encoding="utf-8")
            r = self._run(clone / "scripts" / SCRIPT.name)
            self.assertEqual(r.returncode, 1)
            self.assertIn("policy-approval-governance", r.stdout)


if __name__ == "__main__":
    unittest.main()
