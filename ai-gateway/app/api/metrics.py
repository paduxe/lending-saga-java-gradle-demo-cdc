from fastapi import APIRouter

from app.observability.tracker import get_tracker

router = APIRouter(prefix="/metrics")


@router.get("/ai")
async def ai_metrics():
    return get_tracker().summary()
