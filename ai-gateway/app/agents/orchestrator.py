import json
from pathlib import Path

import anthropic

from app.agents.loan_query import LoanQueryAgent
from app.agents.saga import SagaInvestigatorAgent
from app.agents.operations import OperationsAgent
from app.config import settings
from app.observability.tracker import get_tracker

_client = anthropic.AsyncAnthropic(api_key=settings.anthropic_api_key)
_PROMPTS_DIR = Path(__file__).parent.parent.parent / "prompts"

_AGENTS = {
    "loan_query": LoanQueryAgent(),
    "saga_investigator": SagaInvestigatorAgent(),
    "operations": OperationsAgent(),
}


class OrchestratorAgent:

    def __init__(self):
        self._system_prompt: str | None = None

    def _system(self) -> str:
        if self._system_prompt is None:
            self._system_prompt = (_PROMPTS_DIR / "orchestrator_agent.md").read_text(encoding="utf-8")
        return self._system_prompt

    async def route(self, messages: list[dict]) -> str:
        """Classify intent using full conversation history → return agent name."""
        response = await _client.messages.create(
            model=settings.claude_model,
            max_tokens=128,
            system=self._system(),
            messages=messages,
        )
        text = "".join(b.text for b in response.content if hasattr(b, "text")).strip()
        try:
            data = json.loads(text)
            agent_name = data.get("agent", "loan_query")
        except (json.JSONDecodeError, AttributeError):
            agent_name = "loan_query"

        agent_name = agent_name if agent_name in _AGENTS else "loan_query"
        get_tracker().record_routing(agent_name)
        return agent_name

    async def run(self, messages: list[dict]) -> tuple[str, str]:
        """Route and run. Returns (agent_name, response_text)."""
        agent_name = await self.route(messages)
        agent = _AGENTS[agent_name]
        response = await agent.run(messages)
        return agent_name, response
