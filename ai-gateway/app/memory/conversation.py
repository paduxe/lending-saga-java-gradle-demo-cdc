import json
from typing import Any

import redis.asyncio as aioredis

from app.config import settings

_redis: aioredis.Redis | None = None


async def get_redis() -> aioredis.Redis:
    global _redis
    if _redis is None:
        _redis = await aioredis.from_url(settings.redis_url, decode_responses=True)
    return _redis


async def close_redis() -> None:
    global _redis
    if _redis is not None:
        await _redis.aclose()
        _redis = None


def _key(session_id: str) -> str:
    return f"conv:{session_id}"


async def load_history(session_id: str) -> list[dict[str, Any]]:
    r = await get_redis()
    raw = await r.get(_key(session_id))
    if raw is None:
        return []
    return json.loads(raw)


async def append_turn(session_id: str, user_message: str, assistant_message: str) -> None:
    r = await get_redis()
    key = _key(session_id)

    history = await load_history(session_id)
    history.append({"role": "user", "content": user_message})
    history.append({"role": "assistant", "content": assistant_message})

    max_messages = settings.conversation_max_turns * 2
    if len(history) > max_messages:
        history = history[-max_messages:]

    await r.set(key, json.dumps(history), ex=settings.conversation_ttl_seconds)


async def reset_session(session_id: str) -> None:
    r = await get_redis()
    await r.delete(_key(session_id))
