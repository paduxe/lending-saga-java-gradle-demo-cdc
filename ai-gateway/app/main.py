import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api.agent import router as agent_router
from app.api.chat import router as chat_router
from app.api.health import router as health_router
from app.api.metrics import router as metrics_router
from app.memory.conversation import close_redis
from app.rag.store import get_store

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("Building RAG knowledge index...")
    n = get_store().build_index()
    logger.info(f"RAG index ready: {n} chunks indexed.")
    yield
    await close_redis()


app = FastAPI(
    title="LendingOps AI Gateway",
    version="2.0.0",
    description="AI gateway for the lending saga platform — Multi-Agent, RAG, Observability",
    lifespan=lifespan,
)

app.include_router(health_router)
app.include_router(chat_router, prefix="/api")
app.include_router(agent_router, prefix="/api")
app.include_router(metrics_router, prefix="/api")
