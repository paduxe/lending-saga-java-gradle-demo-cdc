from app.agents.base import AgentBase

_TOOLS = [
    {
        "name": "get_loan",
        "description": "Look up a single loan by ID. Returns status, amount, product type, customer ID.",
        "input_schema": {
            "type": "object",
            "properties": {
                "loan_id": {"type": "string", "description": "The loan UUID"},
            },
            "required": ["loan_id"],
        },
    },
    {
        "name": "list_loans_by_customer",
        "description": "List all loans for a specific customer ID.",
        "input_schema": {
            "type": "object",
            "properties": {
                "customer_id": {"type": "string", "description": "Customer ID, e.g. CUST-100"},
            },
            "required": ["customer_id"],
        },
    },
    {
        "name": "search_loans",
        "description": (
            "Search loans by status. "
            "Valid statuses: PENDING, KYC_REQUESTED, CREDIT_REQUESTED, "
            "DISBURSEMENT_REQUESTED, COMPLETED, FAILED."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "status": {"type": "string", "description": "Loan status to filter by"},
                "page": {"type": "integer", "description": "Page number (0-based, default 0)"},
                "size": {"type": "integer", "description": "Page size (default 20)"},
            },
            "required": ["status"],
        },
    },
]


class LoanQueryAgent(AgentBase):
    name = "loan_query"
    prompt_file = "loan_query_agent.md"
    tool_definitions = _TOOLS
