"""Self-tests for scripts/validate-config-change-audit.py (SPEC-OP-032).

Run: python -m unittest discover -s scripts/tests
No third-party dependencies.
"""
from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "scripts" / "validate-config-change-audit.py"
OBS = REPO / "infrastructure" / "observability"


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
        self.assertIn("scanned 17 config file(s)", result.stdout)

    def test_missing_owner_header_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            clone = self._clone(tmp)
            cfg = clone / "infrastructure" / "observability" / "tempo" / "base" / "tempo.yml"
            text = cfg.read_text(encoding="utf-8")
            broken = text.replace("# owner: platform-observability\n", "", 1)
            self.assertNotEqual(text, broken, "fixture replace did not match — update this test")
            cfg.write_text(broken, encoding="utf-8")
            result = self._run(clone / "scripts" / SCRIPT.name)
            self.assertEqual(result.returncode, 1)
            self.assertIn("missing '# owner:'", result.stdout)

    def test_spec_header_without_a_real_spec_id_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            clone = self._clone(tmp)
            cfg = clone / "infrastructure" / "observability" / "loki" / "base" / "loki.yml"
            text = cfg.read_text(encoding="utf-8")
            broken = text.replace(
                "# spec: SPEC-OP-002; SPEC-OP-013 (ruler wiring, query-safety limits)",
                "# spec: see the design doc",
            )
            self.assertNotEqual(text, broken, "fixture replace did not match — update this test")
            cfg.write_text(broken, encoding="utf-8")
            result = self._run(clone / "scripts" / SCRIPT.name)
            self.assertEqual(result.returncode, 1)
            self.assertIn("names no real SPEC-OP-0xx id", result.stdout)

    def test_rollback_header_without_a_real_mechanism_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            clone = self._clone(tmp)
            cfg = clone / "infrastructure" / "observability" / "collector" / "base" / "config.yaml"
            text = cfg.read_text(encoding="utf-8")
            broken = text.replace(
                "# rollback: git revert <sha>; docker compose -f infrastructure/docker-compose/observability-stack.yml up -d --force-recreate otel-collector",
                "# rollback: ask platform-observability",
            )
            self.assertNotEqual(text, broken, "fixture replace did not match — update this test")
            cfg.write_text(broken, encoding="utf-8")
            result = self._run(clone / "scripts" / SCRIPT.name)
            self.assertEqual(result.returncode, 1)
            self.assertIn("names neither 'git revert' nor 'recreate", result.stdout)

    def test_empty_placeholder_overlay_is_ignored(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            clone = self._clone(tmp)
            stub = (clone / "infrastructure" / "observability" / "collector"
                    / "overlays" / "production" / "empty-stub.yaml")
            stub.parent.mkdir(parents=True, exist_ok=True)
            stub.write_text("", encoding="utf-8")
            result = self._run(clone / "scripts" / SCRIPT.name)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_new_config_file_without_any_header_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            clone = self._clone(tmp)
            new_file = (clone / "infrastructure" / "observability" / "prometheus"
                        / "base" / "extra-scrape.yml")
            new_file.write_text("scrape_configs: []\n", encoding="utf-8")
            result = self._run(clone / "scripts" / SCRIPT.name)
            self.assertEqual(result.returncode, 1)
            self.assertIn("extra-scrape.yml", result.stdout)
            self.assertIn("missing '# owner:'", result.stdout)
            self.assertIn("missing '# spec:'", result.stdout)
            self.assertIn("missing '# rollback:'", result.stdout)


if __name__ == "__main__":
    unittest.main()
