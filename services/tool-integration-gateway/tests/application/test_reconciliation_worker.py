"""04-use-cases UC-TG-005 step 3: "Reconciliation worker queries the external
system or connector status endpoint" — ``ReconciliationWorker`` drives
``ReconcileExecutionUseCase`` over every ``find_reconcilable()`` attempt.
"""

from __future__ import annotations

from tool_gateway.application.commands import ReconcileExecutionCommand
from tool_gateway.workers.reconciliation_worker import ReconciliationWorker


class _FakeExecution:
    def __init__(self, execution_id: str) -> None:
        self.execution_id = execution_id


class _StubToolExecutionRepository:
    def __init__(self, reconcilable: list[_FakeExecution]) -> None:
        self._reconcilable = reconcilable

    def find_reconcilable(self, limit: int) -> list[_FakeExecution]:
        return self._reconcilable[:limit]


class _StubReconcilePort:
    def __init__(self, raise_for: set[str] | None = None) -> None:
        self.calls: list[ReconcileExecutionCommand] = []
        self._raise_for = raise_for or set()

    def reconcile_execution(self, command: ReconcileExecutionCommand):
        self.calls.append(command)
        if command.execution_id in self._raise_for:
            raise RuntimeError("boom")
        return None


def test_run_once_reconciles_every_reconcilable_execution() -> None:
    repository = _StubToolExecutionRepository(reconcilable=[_FakeExecution("exec-1"), _FakeExecution("exec-2")])
    port = _StubReconcilePort()
    worker = ReconciliationWorker(repository, port)

    processed = worker.run_once(batch_size=10)

    assert processed == 2
    assert {c.execution_id for c in port.calls} == {"exec-1", "exec-2"}


def test_run_once_continues_past_a_single_execution_failure() -> None:
    """A failure reconciling one attempt must not stop the batch — mirrors
    ``ExecutionWorker.run_once``'s own try/except-per-item shape.
    """

    repository = _StubToolExecutionRepository(reconcilable=[_FakeExecution("exec-1"), _FakeExecution("exec-2")])
    port = _StubReconcilePort(raise_for={"exec-1"})
    worker = ReconciliationWorker(repository, port)

    processed = worker.run_once(batch_size=10)

    assert processed == 2
    assert {c.execution_id for c in port.calls} == {"exec-1", "exec-2"}


def test_run_once_respects_batch_size() -> None:
    repository = _StubToolExecutionRepository(reconcilable=[_FakeExecution(f"exec-{i}") for i in range(5)])
    port = _StubReconcilePort()
    worker = ReconciliationWorker(repository, port)

    processed = worker.run_once(batch_size=2)

    assert processed == 2
    assert len(port.calls) == 2
