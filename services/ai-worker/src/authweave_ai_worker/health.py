"""Health endpoints for the AI worker."""

from typing import Literal

from fastapi import APIRouter, Response
from pydantic import BaseModel, ConfigDict

router = APIRouter(prefix="/health", tags=["health"])


class HealthResponse(BaseModel):
    """Stable response returned by liveness and readiness probes."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    service: Literal["ai-worker"] = "ai-worker"
    status: Literal["UP"] = "UP"


def _healthy(response: Response) -> HealthResponse:
    response.headers["Cache-Control"] = "no-store"
    return HealthResponse()


@router.get("/liveness", response_model=HealthResponse)
async def liveness(response: Response) -> HealthResponse:
    """Report whether the worker process can serve requests."""

    return _healthy(response)


@router.get("/readiness", response_model=HealthResponse)
async def readiness(response: Response) -> HealthResponse:
    """Report whether the worker can accept typed operations."""

    return _healthy(response)
