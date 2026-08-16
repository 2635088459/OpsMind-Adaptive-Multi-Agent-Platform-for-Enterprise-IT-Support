"""Maps application and domain exceptions to a shared JSON error envelope, mirroring
agent-runtime-service's own interfaces.errors. Never exposes stack traces, internal
exception class names, or persisted payload contents. Error codes match
05-api-contracts §"错误码" where one is named there; codes not named there (e.g. the
various *_NOT_FOUND codes) are this spec's own reasonable additions.
"""

from __future__ import annotations

import logging
from typing import Any

from fastapi import FastAPI, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from memoryknowledge.application.exceptions import (
    DeletionNotAuthorizedException,
    DocumentAlreadyIngestedException,
    DocumentIngestionFailedException,
    GraphNodeNotFoundException,
    GraphTraversalDepthExceededException,
    IdempotencyKeyReusedException,
    KnowledgeDocumentNotFoundException,
    MemoryCandidateConflictingException,
    MemoryCandidateNotFoundException,
    MemoryNotFoundException,
    OptimisticConcurrencyConflictException,
    RetrievalAccessDeniedException,
    WorkingMemoryNotFoundException,
    WorkingMemoryScopeConflictException,
)
from memoryknowledge.domain.exceptions import (
    GraphEdgeMissingEvidenceException,
    InvalidDocumentIngestionTransitionException,
    InvalidGraphNodeTransitionException,
    InvalidMemoryCandidateTransitionException,
    InvalidMemoryVersionTransitionException,
    InvalidWorkingMemoryStateException,
    MemoryCandidateMissingSourceRefException,
    MemoryVersionMissingSourceRefException,
    WorkingMemoryVersionConflictException,
)

logger = logging.getLogger(__name__)


class ErrorDetail(BaseModel):
    code: str
    message: str
    correlation_id: str = ""
    details: dict[str, Any] = {}


class ErrorResponse(BaseModel):
    error: ErrorDetail


def _body(code: str, message: str, request: Request) -> ErrorResponse:
    correlation_id = request.headers.get("X-Correlation-Id", "")
    return ErrorResponse(error=ErrorDetail(code=code, message=message, correlation_id=correlation_id))


def register_exception_handlers(app: FastAPI) -> None:
    @app.exception_handler(RequestValidationError)
    async def handle_validation(request: Request, exc: RequestValidationError) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_400_BAD_REQUEST, content=_body("VALIDATION_ERROR", "The request is invalid.", request).model_dump())

    @app.exception_handler(ValueError)
    async def handle_value_error(request: Request, exc: ValueError) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_400_BAD_REQUEST, content=_body("MEMORY_VALIDATION_FAILED", "The request is invalid.", request).model_dump())

    @app.exception_handler(MemoryCandidateMissingSourceRefException)
    @app.exception_handler(MemoryVersionMissingSourceRefException)
    @app.exception_handler(GraphEdgeMissingEvidenceException)
    async def handle_missing_source_ref(request: Request, exc: Exception) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_400_BAD_REQUEST, content=_body(
            "MEMORY_VALIDATION_FAILED", "The request is missing a required source reference.", request
        ).model_dump())

    @app.exception_handler(WorkingMemoryNotFoundException)
    async def handle_working_memory_not_found(request: Request, exc: WorkingMemoryNotFoundException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_404_NOT_FOUND, content=_body("WORKING_MEMORY_NOT_FOUND", "The working memory was not found.", request).model_dump())

    @app.exception_handler(MemoryCandidateNotFoundException)
    async def handle_candidate_not_found(request: Request, exc: MemoryCandidateNotFoundException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_404_NOT_FOUND, content=_body("MEMORY_CANDIDATE_NOT_FOUND", "The memory candidate was not found.", request).model_dump())

    @app.exception_handler(MemoryNotFoundException)
    async def handle_memory_not_found(request: Request, exc: MemoryNotFoundException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_404_NOT_FOUND, content=_body("MEMORY_NOT_FOUND", "The memory was not found.", request).model_dump())

    @app.exception_handler(KnowledgeDocumentNotFoundException)
    async def handle_document_not_found(request: Request, exc: KnowledgeDocumentNotFoundException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_404_NOT_FOUND, content=_body("KNOWLEDGE_DOCUMENT_NOT_FOUND", "The knowledge document was not found.", request).model_dump())

    @app.exception_handler(GraphNodeNotFoundException)
    async def handle_graph_node_not_found(request: Request, exc: GraphNodeNotFoundException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_404_NOT_FOUND, content=_body("GRAPH_NODE_NOT_FOUND", "The graph node was not found.", request).model_dump())

    @app.exception_handler(DocumentAlreadyIngestedException)
    async def handle_document_already_ingested(request: Request, exc: DocumentAlreadyIngestedException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_409_CONFLICT, content=_body("DOCUMENT_ALREADY_INGESTED", "This document version was already ingested.", request).model_dump())

    @app.exception_handler(DocumentIngestionFailedException)
    async def handle_document_ingestion_failed(request: Request, exc: DocumentIngestionFailedException) -> JSONResponse:
        logger.error("document ingestion failed: %s", exc.reason)
        return JSONResponse(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, content=_body("DOCUMENT_INGESTION_FAILED", "Document ingestion failed.", request).model_dump())

    @app.exception_handler(IdempotencyKeyReusedException)
    async def handle_idempotency_key_reused(request: Request, exc: IdempotencyKeyReusedException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_409_CONFLICT, content=_body("IDEMPOTENCY_KEY_REUSED", "A different idempotency key already produced this result.", request).model_dump())

    @app.exception_handler(RetrievalAccessDeniedException)
    async def handle_retrieval_access_denied(request: Request, exc: RetrievalAccessDeniedException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_403_FORBIDDEN, content=_body("RETRIEVAL_ACCESS_DENIED", "The requester's access scope does not permit this retrieval.", request).model_dump())

    @app.exception_handler(MemoryCandidateConflictingException)
    async def handle_candidate_conflicting(request: Request, exc: MemoryCandidateConflictingException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_409_CONFLICT, content=_body("MEMORY_CANDIDATE_CONFLICTING", "The memory candidate is conflicting and needs manual resolution.", request).model_dump())

    @app.exception_handler(DeletionNotAuthorizedException)
    async def handle_deletion_not_authorized(request: Request, exc: DeletionNotAuthorizedException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_403_FORBIDDEN, content=_body("DELETION_NOT_AUTHORIZED", "Deletion of this memory is not authorized.", request).model_dump())

    @app.exception_handler(GraphTraversalDepthExceededException)
    async def handle_graph_depth_exceeded(request: Request, exc: GraphTraversalDepthExceededException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_400_BAD_REQUEST, content=_body("GRAPH_TRAVERSAL_DEPTH_EXCEEDED", "The requested graph traversal depth exceeds the allowed maximum.", request).model_dump())

    @app.exception_handler(WorkingMemoryVersionConflictException)
    @app.exception_handler(WorkingMemoryScopeConflictException)
    async def handle_working_memory_version_conflict(request: Request, exc: Exception) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_409_CONFLICT, content=_body("WORKING_MEMORY_VERSION_CONFLICT", "The working memory was modified concurrently.", request).model_dump())

    @app.exception_handler(OptimisticConcurrencyConflictException)
    async def handle_optimistic_concurrency_conflict(request: Request, exc: OptimisticConcurrencyConflictException) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_409_CONFLICT, content=_body("VERSION_CONFLICT", "The resource was modified concurrently.", request).model_dump())

    @app.exception_handler(InvalidMemoryCandidateTransitionException)
    @app.exception_handler(InvalidMemoryVersionTransitionException)
    @app.exception_handler(InvalidDocumentIngestionTransitionException)
    @app.exception_handler(InvalidWorkingMemoryStateException)
    @app.exception_handler(InvalidGraphNodeTransitionException)
    async def handle_invalid_transition(request: Request, exc: Exception) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_409_CONFLICT, content=_body("INVALID_STATE_TRANSITION", "The requested transition is not allowed from the current state.", request).model_dump())

    @app.exception_handler(Exception)
    async def handle_unexpected(request: Request, exc: Exception) -> JSONResponse:
        logger.exception("unexpected error handling %s %s", request.method, request.url.path)
        return JSONResponse(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, content=_body("INTERNAL_ERROR", "An unexpected error occurred.", request).model_dump())
