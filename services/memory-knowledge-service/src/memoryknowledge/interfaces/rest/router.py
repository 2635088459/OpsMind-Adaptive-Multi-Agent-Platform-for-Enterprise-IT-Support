"""13-package-and-class-design §"Interfaces": internal REST controller for the
Runtime-facing surface (05-api-contracts §"Runtime API"). Depends only on
ports_in Protocols; never touches a repository or another service directly.
"""

from __future__ import annotations

from uuid import UUID

from fastapi import APIRouter, Depends

from memoryknowledge.application.ports_in import SearchMemoryUseCase, UpdateWorkingMemoryUseCase
from memoryknowledge.container import get_search_memory_port, get_update_working_memory_port
from memoryknowledge.interfaces.rest.mapper import (
    derive_working_memory_id,
    to_search_command,
    to_search_response,
    to_update_working_memory_command,
    to_working_memory_response,
)
from memoryknowledge.interfaces.rest.schemas import SearchRequest, SearchResponse, UpdateWorkingMemoryRequest, WorkingMemoryResponse

router = APIRouter(prefix="/internal/memory/v1", tags=["memory"])


@router.post("/search", response_model=SearchResponse)
def search(request: SearchRequest, port: SearchMemoryUseCase = Depends(get_search_memory_port)) -> SearchResponse:
    """05-api-contracts: `POST /internal/memory/v1/search`."""
    return to_search_response(port.search(to_search_command(request)))


@router.patch("/working-memory/{working_memory_id}", response_model=WorkingMemoryResponse)
def update_working_memory(
    working_memory_id: UUID, request: UpdateWorkingMemoryRequest, port: UpdateWorkingMemoryUseCase = Depends(get_update_working_memory_port),
) -> WorkingMemoryResponse:
    """05-api-contracts: `PATCH /internal/memory/v1/working-memory/{workingMemoryId}`.
    working_memory_id must equal the id domain.working_memory.derive_working_memory_id
    computes from the body's ticket/cycle/workflow scope — a mismatch means the caller
    built the URL from a different scope than the body describes.
    """
    expected_id = derive_working_memory_id(request.ticket_id, request.ticket_cycle_id, request.workflow_instance_id)
    if working_memory_id != expected_id:
        raise ValueError("workingMemoryId path parameter does not match the derived id for the given scope")
    return to_working_memory_response(port.update_working_memory(to_update_working_memory_command(request)))
