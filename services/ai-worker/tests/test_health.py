"""Health endpoint contract tests."""

import pytest
from fastapi.testclient import TestClient

from authweave_ai_worker.main import app


@pytest.mark.parametrize("path", ["/health/liveness", "/health/readiness"])
def test_health_endpoint_reports_up(path: str) -> None:
    with TestClient(app) as client:
        response = client.get(path)

    assert response.status_code == 200
    assert response.json() == {"service": "ai-worker", "status": "UP"}
    assert response.headers["cache-control"] == "no-store"


def test_openapi_exposes_only_typed_health_operations() -> None:
    with TestClient(app) as client:
        response = client.get("/openapi.json")

    assert response.status_code == 200
    paths = response.json()["paths"]
    assert set(paths) == {"/health/liveness", "/health/readiness"}
