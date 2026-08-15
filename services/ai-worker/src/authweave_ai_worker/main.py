"""FastAPI application entry point."""

from fastapi import FastAPI

from authweave_ai_worker import __version__
from authweave_ai_worker.health import router as health_router


def create_app() -> FastAPI:
    """Build the AI worker application."""

    application = FastAPI(
        title="AuthWeave AI Worker",
        description="Typed AI operation boundary for AuthWeave.",
        version=__version__,
    )
    application.include_router(health_router)
    return application


app = create_app()
