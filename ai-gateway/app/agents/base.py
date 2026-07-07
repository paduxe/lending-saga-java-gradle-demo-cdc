import json
import time
from pathlib import Path
from typing import Any

import anthropic

from app.config import settings
from app.observability.tracker import get_tracker
from app.tools.executor import execute_tool

_client = anthropic.AsyncAnthropic(api_key=settings.anthropic_api_key)
_PROMPTS_DIR = Path(__file__).parent.parent.parent / "prompts"

_RAG_TOOL = {
    "name": "search_knowledge_base",
    "description": (
        "Search the platform's internal documentation and architecture guides. "
        "Use when user asks about business rules, architecture, configuration, "
        "how the system works, or any conceptual question not answered by live data tools."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "query": {"type": "string", "description": "The question or topic to search for"},
            "n_results": {"type": "integer", "description": "Number of results to return (default 3)"},
        },
        "required": ["query"],
    },
}


class AgentBase:
    name: str
    prompt_file: str
    tool_definitions: list[dict]

    def __init__(self):
        self._system_prompt: str | None = None
        self._all_tools: list[dict] | None = None

    def system_prompt(self) -> str:
        return (_PROMPTS_DIR / self.prompt_file).read_text(encoding="utf-8")

    def _tools(self) -> list[dict]:
        if self._all_tools is None:
            self._all_tools = self.tool_definitions + [_RAG_TOOL]
        return self._all_tools

    async def run(self, messages: list[dict]) -> str:
        tracker = get_tracker()
        while True:
            t0 = time.monotonic()
            response = await _client.messages.create(
                model=settings.claude_model,
                max_tokens=4096,
                system=self.system_prompt(),
                tools=self._tools(),
                messages=messages,
            )
            latency_ms = (time.monotonic() - t0) * 1000
            tracker.record_llm(
                agent=self.name,
                input_tokens=response.usage.input_tokens,
                output_tokens=response.usage.output_tokens,
                latency_ms=latency_ms,
            )

            if response.stop_reason == "end_turn":
                return _extract_text(response.content)

            if response.stop_reason == "tool_use":
                messages.append({"role": "assistant", "content": response.content})
                tool_results = []
                for block in response.content:
                    if block.type == "tool_use":
                        t1 = time.monotonic()
                        try:
                            result = await execute_tool(block.name, block.input)
                            tracker.record_tool(self.name, block.name, True, (time.monotonic() - t1) * 1000)
                        except Exception as exc:
                            tracker.record_tool(self.name, block.name, False, (time.monotonic() - t1) * 1000)
                            result = {"error": str(exc)}
                        tool_results.append({
                            "type": "tool_result",
                            "tool_use_id": block.id,
                            "content": json.dumps(result, default=str),
                        })
                messages.append({"role": "user", "content": tool_results})
            else:
                return _extract_text(response.content)


def _extract_text(content: list[Any]) -> str:
    return "".join(block.text for block in content if hasattr(block, "text"))
