TOOL_DEFINITIONS = [
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
            "Valid statuses: PENDING, KYC_REQUESTED, CREDIT_REQUESTED, DISBURSEMENT_REQUESTED, COMPLETED, FAILED."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "status": {"type": "string", "description": "Loan status to filter by"},
                "page": {"type": "integer", "description": "Page number (0-based)", "default": 0},
                "size": {"type": "integer", "description": "Page size", "default": 20},
            },
            "required": ["status"],
        },
    },
    {
        "name": "list_dlq_events",
        "description": "Retrieve dead-letter queue events awaiting manual intervention.",
        "input_schema": {
            "type": "object",
            "properties": {
                "limit": {"type": "integer", "description": "Max events to return (default 20)", "default": 20},
            },
            "required": [],
        },
    },
    {
        "name": "replay_dlq_event",
        "description": "Mark a DLQ event as REPLAYED for manual retry processing. Use after investigating the root cause.",
        "input_schema": {
            "type": "object",
            "properties": {
                "event_id": {"type": "string", "description": "The DLQ event ID to replay"},
            },
            "required": ["event_id"],
        },
    },
]
