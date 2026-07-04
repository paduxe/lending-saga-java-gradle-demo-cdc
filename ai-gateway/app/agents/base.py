import json
from pathlib import Path
from typing import Any

import anthropic

from app.config import settings
from app.tools.executor import execute_tool

_client = anthropic.AsyncAnthropic(api_key=settings.anthropic_api_key)
_PROMPTS_DIR = Path(__file__).parent.parent.parent / "prompts"


class AgentBase:
    name: str
    prompt_file: str
    tool_definitions: list[dict]

    def __init__(self):
        self._system_prompt: str | None = None

    def system_prompt(self) -> str:
        if self._system_prompt is None:
            self._system_prompt = (_PROMPTS_DIR / self.prompt_file).read_text(encoding="utf-8")
        return self._system_prompt

    async def run(self, messages: list[dict]) -> str:
        while True:
            response = await _client.messages.create(
                model=settings.claude_model,
                max_tokens=4096,
                system=self.system_prompt(),
                tools=self.tool_definitions,
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


def _extract_text(content: list[Any]) -> str:
    return "".join(block.text for block in content if hasattr(block, "text"))
