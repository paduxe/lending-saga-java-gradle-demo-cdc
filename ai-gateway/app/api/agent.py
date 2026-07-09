import uuid

from fastapi import APIRouter, Header
from pydantic import BaseModel

from app.agents.orchestrator import OrchestratorAgent
from app.memory.conversation import append_turn, load_history
from app.security.output_filter import check_output
from app.security.pii import scan

router = APIRouter(prefix="/agent")

_orchestrator = OrchestratorAgent()


class AgentRequest(BaseModel):
    message: str


class AgentResponse(BaseModel):
    session_id: str
    agent: str
    response: str
    pii_detected_in_input: bool
    output_clean: bool


@router.post("", response_model=AgentResponse)
async def agent_chat(
    body: AgentRequest,
    x_session_id: str | None = Header(default=None),
):
    session_id = x_session_id or str(uuid.uuid4())

    # PII detection: mask input trước khi gửi LLM
    safe_input, input_findings = scan(body.message)

    history = await load_history(session_id)
    messages = history + [{"role": "user", "content": safe_input}]

    agent_name, response_text = await _orchestrator.run(messages)

    # Output filter: check LLM response có leak PII không
    filter_result = check_output(response_text)

    await append_turn(session_id, safe_input, filter_result["response"])

    return AgentResponse(
        session_id=session_id,
        agent=agent_name,
        response=filter_result["response"],
        pii_detected_in_input=len(input_findings) > 0,
        output_clean=filter_result["clean"],
    )
