from app.agents.base import AgentBase

_TOOLS = [
    {
        "name": "get_saga",
        "description": (
            "Track a saga's full state machine lifecycle. "
            "States: INIT → KYC_REQUESTED → CREDIT_REQUESTED → DISBURSEMENT_REQUESTED → COMPLETED/FAILED."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "saga_id": {"type": "string", "description": "The saga UUID"},
            },
            "required": ["saga_id"],
        },
    },
    {
        "name": "list_dlq_events",
        "description": "Retrieve dead-letter queue events awaiting manual intervention.",
        "input_schema": {
            "type": "object",
            "properties": {
                "limit": {"type": "integer", "description": "Max events to return (default 20)"},
            },
            "required": [],
        },
    },
]


class SagaInvestigatorAgent(AgentBase):
    name = "saga_investigator"
    prompt_file = "saga_investigator_agent.md"
    tool_definitions = _TOOLS
