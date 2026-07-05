import json
from typing import Any

import httpx

from app.config import settings
from app.rag.store import get_store

_TIMEOUT = httpx.Timeout(10.0)


async def execute_tool(name: str, tool_input: dict[str, Any]) -> Any:
    if name == "search_knowledge_base":
        results = get_store().search(tool_input["query"], n_results=tool_input.get("n_results", 3))
        if not results:
            return {"result": "No relevant documentation found."}
        parts = [f"[{r['source']}]\n{r['text']}" for r in results]
        return {"result": "\n\n---\n\n".join(parts)}

    async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
        match name:
            case "get_loan":
                r = await client.get(
                    f"{settings.loan_service_url}/api/queries/loans/{tool_input['loan_id']}"
                )
                r.raise_for_status()
                return r.json()

            case "get_saga":
                r = await client.get(
                    f"{settings.orchestrator_service_url}/api/sagas/{tool_input['saga_id']}"
                )
                r.raise_for_status()
                return r.json()

            case "list_loans_by_customer":
                r = await client.get(
                    f"{settings.loan_service_url}/api/queries/loans/customer/{tool_input['customer_id']}"
                )
                r.raise_for_status()
                return r.json()

            case "search_loans":
                r = await client.get(
                    f"{settings.loan_service_url}/api/queries/loans/status/{tool_input['status']}",
                    params={"page": tool_input.get("page", 0), "size": tool_input.get("size", 20)},
                )
                r.raise_for_status()
                return r.json()

            case "list_dlq_events":
                r = await client.get(
                    f"{settings.orchestrator_service_url}/api/dlq",
                    params={"limit": tool_input.get("limit", 20)},
                )
                r.raise_for_status()
                return r.json()

            case "replay_dlq_event":
                r = await client.post(
                    f"{settings.orchestrator_service_url}/api/dlq/{tool_input['event_id']}/replay"
                )
                r.raise_for_status()
                return r.json()

            case _:
                raise ValueError(f"Unknown tool: {name}")
