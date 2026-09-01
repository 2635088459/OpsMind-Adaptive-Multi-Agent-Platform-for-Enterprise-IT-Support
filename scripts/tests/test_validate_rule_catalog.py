"""Self-tests for scripts/validate-rule-catalog.py (SPEC-OP-020 / SPEC-OP-024).

Run: uv run --with pyyaml python -m unittest discover -s scripts/tests
"""
from __future__ import annotations

import importlib.util
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "scripts" / "validate-rule-catalog.py"
OBS = REPO / "infrastructure" / "observability"

_spec = importlib.util.spec_from_file_location("vrc", SCRIPT)
vrc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(vrc)  # type: ignore[union-attr]


class UnitTests(unittest.TestCase):
    def test_runbook_url_regex(self) -> None:
        m = vrc.RUNBOOK_URL_RE.search(
            "https://github.com/opsmind/observability/blob/main/infrastructure/observability/runbooks/TargetDown.md")
        self.assertEqual(m.group(1), "TargetDown.md")

    def test_recording_name_convention(self) -> None:
        self.assertTrue(vrc.RECORDING_NAME_RE.match("job:up:ratio"))
        self.assertTrue(vrc.RECORDING_NAME_RE.match("http:duration:p95"))
        self.assertFalse(vrc.RECORDING_NAME_RE.match("http:duration_p95:5m"))
        self.assertFalse(vrc.RECORDING_NAME_RE.match("no_colons_here"))

    def test_alert_missing_severity_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "f.yml"
            p.write_text("""
groups:
  - name: g
    rules:
      - alert: Foo
        expr: up == 0
        labels: { owner: platform-observability }
        annotations: { summary: s, description: d, runbook_url: "x/runbooks/A.md", dashboard: D }
""", encoding="utf-8")
            vrc.errors.clear()
            vrc.check_alerting_file(p, set(), {})
            self.assertTrue(any("labels.severity" in e for e in vrc.errors))

    def test_alert_missing_runbook_annotation_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "f.yml"
            p.write_text("""
groups:
  - name: g
    rules:
      - alert: Foo
        expr: up == 0
        labels: { severity: warning, owner: platform-observability }
        annotations: { summary: s, description: d, dashboard: D }
""", encoding="utf-8")
            vrc.errors.clear()
            vrc.check_alerting_file(p, set(), {})
            self.assertTrue(any("annotations.runbook_url" in e for e in vrc.errors))

    def test_nonexistent_runbook_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "f.yml"
            p.write_text("""
groups:
  - name: g
    rules:
      - alert: Foo
        expr: up == 0
        labels: { severity: warning, owner: platform-observability }
        annotations: { summary: s, description: d, dashboard: D,
          runbook_url: "https://x/runbooks/DoesNotExist12345.md" }
""", encoding="utf-8")
            vrc.errors.clear()
            vrc.check_alerting_file(p, set(), {})
            self.assertTrue(any("does not exist" in e for e in vrc.errors))

    def test_valid_alert_passes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "f.yml"
            p.write_text("""
groups:
  - name: g
    rules:
      - alert: Foo
        expr: up == 0
        labels: { severity: warning, owner: platform-observability }
        annotations: { summary: s, description: d, dashboard: D,
          runbook_url: "https://x/runbooks/TargetDown.md" }
""", encoding="utf-8")
            vrc.errors.clear()
            refs: set[str] = set()
            sevs: dict[str, set[str]] = {}
            vrc.check_alerting_file(p, refs, sevs)
            self.assertEqual(vrc.errors, [])
            self.assertIn("TargetDown.md", refs)
            self.assertEqual(sevs["TargetDown.md"], {"warning"})


class RunbookStructureTests(unittest.TestCase):
    """SPEC-OP-024 — the runbook BODY (not just its file existence) must have
    every required section, filled in for real."""

    def test_split_sections(self) -> None:
        sections = vrc._split_sections("intro\n## Impact\nline1\nline2\n## Detection\nline3\n")
        self.assertEqual(sections["Impact"], "line1\nline2")
        self.assertEqual(sections["Detection"], "line3")

    def _write_runbook(self, tmp: str, name: str, sections: dict[str, str]) -> None:
        body = "\n".join(f"## {h}\n\n{c}\n" for h, c in sections.items())
        (Path(tmp) / name).write_text(f"# {name}\n\n> owner: x\n\n{body}", encoding="utf-8")

    def _full_sections(self, **overrides: str) -> dict[str, str]:
        base = {h: f"Real, specific {h.lower()} content for this incident class." for h in vrc.REQUIRED_RUNBOOK_SECTIONS}
        base.update(overrides)
        return base

    def test_missing_section_on_critical_runbook_is_an_error(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            sections = self._full_sections()
            del sections["Escalation"]
            self._write_runbook(tmp, "R.md", sections)
            orig = vrc.RUNBOOKS
            vrc.RUNBOOKS = Path(tmp)
            try:
                vrc.errors.clear()
                vrc.warnings.clear()
                vrc.check_runbook_structure({"R.md": {"critical"}})
                self.assertTrue(any("missing required '## Escalation'" in e for e in vrc.errors))
            finally:
                vrc.RUNBOOKS = orig

    def test_missing_section_on_warning_only_runbook_is_a_warning_not_an_error(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            sections = self._full_sections()
            del sections["Escalation"]
            self._write_runbook(tmp, "R.md", sections)
            orig = vrc.RUNBOOKS
            vrc.RUNBOOKS = Path(tmp)
            try:
                vrc.errors.clear()
                vrc.warnings.clear()
                vrc.check_runbook_structure({"R.md": {"warning"}})
                self.assertEqual(vrc.errors, [])
                self.assertTrue(any("missing required '## Escalation'" in w for w in vrc.warnings))
            finally:
                vrc.RUNBOOKS = orig

    def test_leftover_template_placeholder_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            sections = self._full_sections(Escalation="Who to page and when (owner → secondary → domain owner).")
            self._write_runbook(tmp, "R.md", sections)
            orig = vrc.RUNBOOKS
            vrc.RUNBOOKS = Path(tmp)
            try:
                vrc.errors.clear()
                vrc.warnings.clear()
                vrc.check_runbook_structure({"R.md": {"critical"}})
                self.assertTrue(any("literal TEMPLATE.md boilerplate" in e for e in vrc.errors))
            finally:
                vrc.RUNBOOKS = orig

    def test_fully_filled_runbook_passes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            self._write_runbook(tmp, "R.md", self._full_sections())
            orig = vrc.RUNBOOKS
            vrc.RUNBOOKS = Path(tmp)
            try:
                vrc.errors.clear()
                vrc.warnings.clear()
                vrc.check_runbook_structure({"R.md": {"critical"}})
                self.assertEqual(vrc.errors, [])
                self.assertEqual(vrc.warnings, [])
            finally:
                vrc.RUNBOOKS = orig


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

    def test_real_tree_passes(self) -> None:
        r = self._run(SCRIPT)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_missing_owner_label_fails_on_real_tree(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            clone = self._clone(tmp)
            f = (clone / "infrastructure" / "observability" / "rules" / "alerting" / "http-server.yml")
            text = f.read_text(encoding="utf-8")
            broken = text.replace("owner: platform-observability\n          namespace",
                                   "namespace", 1)
            self.assertNotEqual(text, broken, "fixture replace did not match — update this test")
            f.write_text(broken, encoding="utf-8")
            r = self._run(clone / "scripts" / SCRIPT.name)
            self.assertEqual(r.returncode, 1)
            self.assertIn("labels.owner", r.stdout)

    def test_stubbed_runbook_section_fails_on_real_tree(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            clone = self._clone(tmp)
            f = clone / "infrastructure" / "observability" / "runbooks" / "HttpGoldenSignals.md"
            text = f.read_text(encoding="utf-8")
            broken = re.sub(
                r"## Escalation\n\n.*?\n\n## Post-incident",
                "## Escalation\n\n## Post-incident",
                text, count=1, flags=re.S,
            )
            self.assertNotEqual(text, broken, "fixture regex did not match — update this test")
            f.write_text(broken, encoding="utf-8")
            r = self._run(clone / "scripts" / SCRIPT.name)
            self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
            self.assertIn("HttpGoldenSignals.md", r.stdout)
            self.assertIn("Escalation", r.stdout)

    def test_loki_ruler_rules_are_scanned(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            clone = self._clone(tmp)
            # SPEC-OP-031 moved this rule from the single "fake" tenant directory
            # (Loki's synthetic single-tenant id) into one real copy per producing-
            # domain tenant — any one of them exercises the same scanning logic.
            f = (clone / "infrastructure" / "observability" / "loki" / "rules" / "shared" / "log-quality.yaml")
            text = f.read_text(encoding="utf-8")
            broken = text.replace(
                'runbook_url: "https://github.com/opsmind/observability/blob/main/infrastructure/observability/runbooks/StructuredLogContractViolation.md"',
                'runbook_url: "https://github.com/opsmind/observability/blob/main/infrastructure/observability/runbooks/DoesNotExist999.md"')
            self.assertNotEqual(text, broken, "fixture replace did not match — update this test")
            f.write_text(broken, encoding="utf-8")
            r = self._run(clone / "scripts" / SCRIPT.name)
            self.assertEqual(r.returncode, 1)
            self.assertIn("DoesNotExist999.md", r.stdout)


if __name__ == "__main__":
    unittest.main()
