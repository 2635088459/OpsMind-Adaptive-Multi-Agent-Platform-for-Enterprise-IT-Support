"""Self-tests for scripts/validate-dashboards.py (SPEC-OP-016).

Run: uv run --with pyyaml python -m unittest discover -s scripts/tests
"""
from __future__ import annotations

import importlib.util
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "scripts" / "validate-dashboards.py"
OBS = REPO / "infrastructure" / "observability"

_spec = importlib.util.spec_from_file_location("vd", SCRIPT)
vd = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(vd)  # type: ignore[union-attr]

GOOD_META = {
    "owner": "platform-observability", "version": "1.0.0", "spec": "SPEC-OP-016",
    "access_policy": "viewer: all-engineering; edit: platform-observability",
    "retention": "n/a (view only)", "runbook": "self",
    "rollback": "git revert <sha>; re-run grafana provisioning",
    "audit_ref": "docs/traceability/domains/08-observability-platform/SPEC-OP-016-traceability.md",
}


class UnitTests(unittest.TestCase):
    def test_collect_datasource_refs(self) -> None:
        refs: list[str] = []
        vd.collect_datasource_refs(
            [{"datasource": {"type": "prometheus", "uid": "prometheus"}, "targets": []}], refs)
        self.assertEqual(refs, ["prometheus"])

    def test_valid_dashboard_passes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "d.json"
            p.write_text(json.dumps({
                "__opsmind_meta": GOOD_META, "uid": "test-uid",
                "tags": ["opsmind", "owner:platform-observability", "v:1.0.0"],
                "panels": [],
            }), encoding="utf-8")
            vd.errors.clear()
            vd.warnings.clear()
            vd.check_dashboard(p, set(), {})
            self.assertEqual(vd.errors, [])

    def test_missing_meta_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "d.json"
            p.write_text(json.dumps({"uid": "x", "panels": []}), encoding="utf-8")
            vd.errors.clear()
            vd.check_dashboard(p, set(), {})
            self.assertTrue(any("__opsmind_meta" in e for e in vd.errors))

    def test_duplicate_uid_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "d.json"
            p.write_text(json.dumps({
                "__opsmind_meta": GOOD_META, "uid": "dupe",
                "tags": ["opsmind", "owner:platform-observability", "v:1.0.0"],
                "panels": [],
            }), encoding="utf-8")
            vd.errors.clear()
            vd.check_dashboard(p, set(), {"dupe": "some/other/file.json"})
            self.assertTrue(any("duplicates" in e for e in vd.errors))

    def test_unknown_datasource_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "d.json"
            p.write_text(json.dumps({
                "__opsmind_meta": GOOD_META, "uid": "test-uid2",
                "tags": ["opsmind", "owner:platform-observability", "v:1.0.0"],
                "panels": [{"datasource": {"type": "prometheus", "uid": "not-real"}, "targets": []}],
            }), encoding="utf-8")
            vd.errors.clear()
            vd.check_dashboard(p, {"prometheus", "loki", "tempo"}, {})
            self.assertTrue(any("unknown datasource" in e for e in vd.errors))


class EndToEndTests(unittest.TestCase):
    def _run(self, script: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run([sys.executable, str(script)], capture_output=True, text=True)

    def test_real_tree_passes(self) -> None:
        r = self._run(SCRIPT)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_broken_json_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            clone = Path(tmp) / "repo"
            (clone / "infrastructure").mkdir(parents=True)
            (clone / "scripts").mkdir()
            shutil.copytree(OBS, clone / "infrastructure" / "observability")
            shutil.copy(SCRIPT, clone / "scripts" / SCRIPT.name)
            d = clone / "infrastructure" / "observability" / "dashboards" / "observability-platform-self.json"
            d.write_text(d.read_text(encoding="utf-8") + "{not json", encoding="utf-8")
            r = self._run(clone / "scripts" / SCRIPT.name)
            self.assertEqual(r.returncode, 1)
            self.assertIn("invalid JSON", r.stdout)


if __name__ == "__main__":
    unittest.main()
