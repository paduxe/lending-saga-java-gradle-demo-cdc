import uuid

from fastapi import APIRouter, Header
from pydantic import BaseModel

from app.agents.orchestrator import OrchestratorAgent
from app.memory.conversation import append_turn, load_history

router = APIRouter(prefix="/agent")

_orchestrator = OrchestratorAgent()


class AgentRequest(BaseModel):
    message: str


class AgentResponse(BaseModel):
    session_id: str
    agent: str
    response: str


@router.post("", response_model=AgentResponse)
async def agent_chat(
    body: AgentRequest,
    x_session_id: str | None = Header(default=None),
):
    session_id = x_session_id or str(uuid.uuid4())
    history = await load_history(session_id)
    messages = history + [{"role": "user", "content": body.message}]

    agent_name, response_text = await _orchestrator.run(messages)
    await append_turn(session_id, body.message, response_text)

    return AgentResponse(
        session_id=session_id,
        agent=agent_name,
        response=response_text,
    )
