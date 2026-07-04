from app.agents.base import AgentBase

_TOOLS = [
    {
        "name": "replay_dlq_event",
        "description": "Mark a DLQ event as REPLAYED for retry. Use after investigating the root cause and confirming with the user.",
        "input_schema": {
            "type": "object",
            "properties": {
                "event_id": {"type": "string", "description": "The DLQ event ID to replay"},
            },
            "required": ["event_id"],
        },
    },
]


class OperationsAgent(AgentBase):
    name = "operations"
    prompt_file = "operations_agent.md"
    tool_definitions = _TOOLS
