from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api.chat import router as chat_router
from app.api.health import router as health_router
from app.memory.conversation import close_redis


@asynccontextmanager
async def lifespan(app: FastAPI):
    yield
    await close_redis()


app = FastAPI(
    title="LendingOps AI Gateway",
    version="1.0.0",
    description="AI gateway for the lending saga platform — LLM chat, tool calling, RAG",
    lifespan=lifespan,
)

app.include_router(health_router)
app.include_router(chat_router, prefix="/api")
