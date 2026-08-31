"""Self-tests for scripts/validate-observability-layout.py (SPEC-OP-001).

Run: python -m unittest discover -s scripts/tests
No third-party dependencies.
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
SCRIPT = REPO / "scripts" / "validate-observability-layout.py"
OBS = REPO / "infrastructure" / "observability"

_spec = importlib.util.spec_from_file_location("vol", SCRIPT)
vol = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(vol)  # type: ignore[union-attr]


class RegexTests(unittest.TestCase):
    def test_floating_tags_rejected(self) -> None:
        for bad in ("latest", "stable", "main", "v3", "3", "3.1", "v3.1"):
            self.assertTrue(vol.FLOATING_TAG.match(bad), bad)

    def test_exact_versions_accepted(self) -> None:
        for good in ("0.116.0", "v3.1.0", "2.7.1", "v0.28.0"):
            self.assertFalse(vol.FLOATING_TAG.match(good), good)

    def test_semver(self) -> None:
        self.assertTrue(vol.SEMVER.match("1.0.0"))
        self.assertFalse(vol.SEMVER.match("1.0"))
        self.assertFalse(vol.SEMVER.match("v1.0.0"))


class MetaParseTests(unittest.TestCase):
    def test_md_meta(self) -> None:
        meta = vol._parse_md_meta(
            "# Title\n\n> owner: platform-observability\n> version: 1.2.3\n\nbody\n"
        )
        self.assertEqual(meta["owner"], "platform-observability")
        self.assertEqual(meta["version"], "1.2.3")

    def test_yaml_meta(self) -> None:
        meta = vol._parse_yaml_meta(
            "# meta.owner: platform-observability\n# meta.version: 0.1.0\ngroups: []\n"
        )
        self.assertEqual(meta["owner"], "platform-observability")
        self.assertEqual(meta["version"], "0.1.0")


class EndToEndTests(unittest.TestCase):
    def _run(self, script: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(script)],
            capture_output=True,
            text=True,
        )

    def _clone(self, tmp: str) -> Path:
        clone = Path(tmp) / "repo"
        (clone / "infrastructure").mkdir(parents=True)
        (clone / "scripts").mkdir()
        shutil.copytree(OBS, clone / "infrastructure" / "observability")
        shutil.copy(SCRIPT, clone / "scripts" / SCRIPT.name)
        return clone

    def test_real_tree_passes(self) -> None:
        result = self._run(SCRIPT)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_missing_adr_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            clone = self._clone(tmp)
            (clone / "infrastructure" / "observability" / "docs" / "adr"
             / "0004-observability-never-mutates-business-state.md").unlink()
            result = self._run(clone / "scripts" / SCRIPT.name)
            self.assertEqual(result.returncode, 1)
            self.assertIn("0004", result.stdout)

    def test_floating_tag_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            clone = self._clone(tmp)
            ve = clone / "infrastructure" / "observability" / "versions.env"
            ve.write_text(
                ve.read_text(encoding="utf-8").replace(
                    "PROMETHEUS_TAG=v3.1.0", "PROMETHEUS_TAG=latest"
                ),
                encoding="utf-8",
            )
            result = self._run(clone / "scripts" / SCRIPT.name)
            self.assertEqual(result.returncode, 1)
            self.assertIn("floating tag", result.stdout)


if __name__ == "__main__":
    unittest.main()
