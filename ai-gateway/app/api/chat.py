import json
import uuid
from pathlib import Path
from typing import AsyncIterator

import anthropic
from fastapi import APIRouter, Header
from pydantic import BaseModel
from sse_starlette.sse import EventSourceResponse

from app.config import settings
from app.memory.conversation import append_turn, load_history
from app.tools.definitions import TOOL_DEFINITIONS
from app.tools.executor import execute_tool

router = APIRouter(prefix="/chat")

_system_prompt: str | None = None
_client = anthropic.AsyncAnthropic(api_key=settings.anthropic_api_key)


def _get_system_prompt() -> str:
    global _system_prompt
    if _system_prompt is None:
        prompt_file = Path(__file__).parent.parent.parent / "prompts" / "system_prompt.md"
        _system_prompt = prompt_file.read_text(encoding="utf-8")
    return _system_prompt


class ChatRequest(BaseModel):
    message: str


class ChatResponse(BaseModel):
    session_id: str
    response: str


@router.post("", response_model=ChatResponse)
async def chat(
    body: ChatRequest,
    x_session_id: str | None = Header(default=None),
):
    session_id = x_session_id or str(uuid.uuid4())
    history = await load_history(session_id)
    messages = history + [{"role": "user", "content": body.message}]

    assistant_text = await _run_agent_loop(messages)
    await append_turn(session_id, body.message, assistant_text)
    return ChatResponse(session_id=session_id, response=assistant_text)


@router.post("/stream")
async def chat_stream(
    body: ChatRequest,
    x_session_id: str | None = Header(default=None),
):
    session_id = x_session_id or str(uuid.uuid4())
    history = await load_history(session_id)
    messages = history + [{"role": "user", "content": body.message}]

    collected: list[str] = []

    async def event_generator() -> AsyncIterator[dict]:
        yield {"event": "session", "data": session_id}

        async with _client.messages.stream(
            model=settings.claude_model,
            max_tokens=4096,
            system=_get_system_prompt(),
            tools=TOOL_DEFINITIONS,
            messages=messages,
        ) as stream:
            async for text in stream.text_stream:
                collected.append(text)
                yield {"event": "text", "data": text}

        full_response = "".join(collected)
        await append_turn(session_id, body.message, full_response)
        yield {"event": "done", "data": "[DONE]"}

    return EventSourceResponse(event_generator())


async def _run_agent_loop(messages: list[dict]) -> str:
    """Agentic loop: calls Claude, executes tool_use blocks, feeds results back until end_turn."""
    while True:
        response = await _client.messages.create(
            model=settings.claude_model,
            max_tokens=4096,
            system=_get_system_prompt(),
            tools=TOOL_DEFINITIONS,
            messages=messages,
        )

        if response.stop_reason == "end_turn":
            return _extract_text(response.content)

        if response.stop_reason == "tool_use":
            messages.append({"role": "assistant", "content": response.content})

            tool_results = []
            for block in response.content:
                if block.type == "tool_use":
                    try:
                        result = await execute_tool(block.name, block.input)
                    except Exception as exc:
                        result = {"error": str(exc)}
                    tool_results.append({
                        "type": "tool_result",
                        "tool_use_id": block.id,
                        "content": json.dumps(result, default=str),
                    })

            messages.append({"role": "user", "content": tool_results})
        else:
            return _extract_text(response.content)


def _extract_text(content: list) -> str:
    return "".join(block.text for block in content if hasattr(block, "text"))
