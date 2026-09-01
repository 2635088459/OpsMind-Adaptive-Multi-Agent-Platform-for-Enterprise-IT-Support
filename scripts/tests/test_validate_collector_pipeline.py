"""Self-tests for scripts/validate-collector-pipeline.py (SPEC-OP-009).

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
SCRIPT = REPO / "scripts" / "validate-collector-pipeline.py"
COLLECTOR = REPO / "infrastructure" / "observability" / "collector" / "base" / "config.yaml"

_spec = importlib.util.spec_from_file_location("vcp", SCRIPT)
vcp = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(vcp)  # type: ignore[union-attr]


class UnitTests(unittest.TestCase):
    def test_valid_pipeline_passes(self) -> None:
        vcp.errors.clear()
        vcp.check_pipeline("traces", ["memory_limiter", "resource", "transform/governance", "batch"])
        self.assertEqual(vcp.errors, [])

    def test_missing_governance_fails(self) -> None:
        vcp.errors.clear()
        vcp.check_pipeline("traces", ["memory_limiter", "resource", "batch"])
        self.assertTrue(any("transform/governance" in e for e in vcp.errors))

    def test_value_mutating_processor_after_governance_fails(self) -> None:
        vcp.errors.clear()
        vcp.check_pipeline("traces", ["memory_limiter", "transform/governance", "resource", "batch"])
        self.assertTrue(any("run AFTER transform/governance" in e for e in vcp.errors))

    def test_tail_sampling_after_governance_is_allowed(self) -> None:
        vcp.errors.clear()
        vcp.check_pipeline("traces", ["memory_limiter", "transform/governance", "tail_sampling", "batch"])
        self.assertEqual(vcp.errors, [])

    def test_out_of_order_fails(self) -> None:
        vcp.errors.clear()
        vcp.check_pipeline(
            "traces",
            ["memory_limiter", "transform/baggage-contract", "resourcedetection",
             "transform/governance", "batch"],
        )
        self.assertTrue(any("out of MASTER_ORDER" in e for e in vcp.errors))

    def test_duplicate_processor_fails(self) -> None:
        vcp.errors.clear()
        vcp.check_pipeline(
            "traces",
            ["memory_limiter", "resource", "resource", "transform/governance", "batch"],
        )
        self.assertTrue(any("more than once" in e for e in vcp.errors))

    def test_signal_restriction(self) -> None:
        vcp.errors.clear()
        vcp.check_signal_restriction({
            "traces": ["memory_limiter", "transform/log-schema-contract", "batch"],
            "logs": ["memory_limiter", "transform/log-schema-contract", "batch"],
        })
        self.assertTrue(any("restricted to the 'logs' pipeline" in e for e in vcp.errors))


class EndToEndTests(unittest.TestCase):
    def _clone(self, tmp: str) -> Path:
        clone = Path(tmp) / "repo"
        (clone / "infrastructure" / "observability" / "collector" / "base").mkdir(parents=True)
        (clone / "scripts").mkdir()
        shutil.copy(COLLECTOR, clone / "infrastructure" / "observability" / "collector" / "base" / "config.yaml")
        shutil.copy(SCRIPT, clone / "scripts" / SCRIPT.name)
        return clone

    def _run(self, clone: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run([sys.executable, str(clone / "scripts" / SCRIPT.name)],
                              capture_output=True, text=True)

    def test_real_config_passes(self) -> None:
        r = subprocess.run([sys.executable, str(SCRIPT)], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_swapped_order_in_real_config_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            clone = self._clone(tmp)
            cfg = clone / "infrastructure" / "observability" / "collector" / "base" / "config.yaml"
            text = cfg.read_text(encoding="utf-8")
            # Swap transform/governance to run BEFORE transform/resource-contract in
            # the traces pipeline only — a real, subtle out-of-order regression.
            broken = text.replace(
                "processors: [memory_limiter, resourcedetection, resource, transform/resource-contract, transform/baggage-contract, filter/noise, attributes/semconv-compat, transform/trace-priority, transform/governance, tail_sampling, batch]\n      exporters: [routing/traces-tenant]",
                "processors: [memory_limiter, resourcedetection, resource, transform/governance, transform/resource-contract, transform/baggage-contract, filter/noise, attributes/semconv-compat, transform/trace-priority, tail_sampling, batch]\n      exporters: [routing/traces-tenant]",
            )
            self.assertNotEqual(text, broken, "fixture replace did not match — update this test")
            cfg.write_text(broken, encoding="utf-8")
            r = self._run(clone)
            self.assertEqual(r.returncode, 1)
            self.assertIn("out of MASTER_ORDER", r.stdout)

    def test_metric_cardinality_in_traces_pipeline_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            clone = self._clone(tmp)
            cfg = clone / "infrastructure" / "observability" / "collector" / "base" / "config.yaml"
            text = cfg.read_text(encoding="utf-8")
            broken = text.replace(
                "processors: [memory_limiter, resourcedetection, resource, transform/resource-contract, transform/baggage-contract, filter/noise, attributes/semconv-compat, transform/trace-priority, transform/governance, tail_sampling, batch]\n      exporters: [routing/traces-tenant]",
                "processors: [memory_limiter, resourcedetection, resource, transform/resource-contract, transform/baggage-contract, filter/noise, attributes/semconv-compat, transform/metric-cardinality, transform/trace-priority, transform/governance, tail_sampling, batch]\n      exporters: [routing/traces-tenant]",
            )
            self.assertNotEqual(text, broken, "fixture replace did not match — update this test")
            cfg.write_text(broken, encoding="utf-8")
            r = self._run(clone)
            self.assertEqual(r.returncode, 1)
            self.assertIn("restricted to", r.stdout)


if __name__ == "__main__":
    unittest.main()
