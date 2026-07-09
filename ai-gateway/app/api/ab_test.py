import asyncio
import time

import anthropic
from fastapi import APIRouter
from pydantic import BaseModel

from app.config import settings
from app.evals.judge import judge_response
from app.rag.store import get_store

router = APIRouter(prefix="/ab-test")

_client = anthropic.AsyncAnthropic(api_key=settings.anthropic_api_key)

_MODELS = {
    "haiku":  "claude-haiku-4-5-20251001",
    "sonnet": "claude-sonnet-4-6",
}

# Cost per 1M tokens (USD)
_COST = {
    "haiku":  {"input": 0.8,  "output": 4.0},
    "sonnet": {"input": 3.0,  "output": 15.0},
}

_SYSTEM = "You are a helpful assistant for a lending platform. Answer concisely and accurately."

_QUERIES = [
    "What is the fraud score threshold to block a loan?",
    "How does the transactional outbox pattern work?",
    "What caching strategy is used for saga state?",
    "How does database sharding work in the loan service?",
    "How does the system prevent duplicate Kafka message processing?",
]


async def _run_model(model_key: str, query: str) -> dict:
    model_id = _MODELS[model_key]
    context_chunks = get_store().search(query, n_results=3)
    context = "\n".join(c["text"] for c in context_chunks)

    messages = [{"role": "user", "content": f"Context:\n{context}\n\nQuestion: {query}"}]

    t0 = time.monotonic()
    response = await _client.messages.create(
        model=model_id,
        max_tokens=512,
        system=_SYSTEM,
        messages=messages,
    )
    latency_ms = round((time.monotonic() - t0) * 1000)

    text = "".join(b.text for b in response.content if hasattr(b, "text"))
    cost = round(
        (response.usage.input_tokens * _COST[model_key]["input"] +
         response.usage.output_tokens * _COST[model_key]["output"]) / 1_000_000,
        6,
    )

    scores = await judge_response(query, context_chunks, text)

    return {
        "model": model_key,
        "latency_ms": latency_ms,
        "input_tokens": response.usage.input_tokens,
        "output_tokens": response.usage.output_tokens,
        "cost_usd": cost,
        "judge_score": scores.get("overall", 0),
        "response_preview": text[:150] + "..." if len(text) > 150 else text,
    }


class ABTestRequest(BaseModel):
    queries: list[str] | None = None   # None = dùng default queries


@router.post("/run")
async def run_ab_test(body: ABTestRequest = ABTestRequest()):
    queries = body.queries or _QUERIES

    # Chạy tất cả (query × model) song song
    tasks = [
        _run_model(model_key, query)
        for query in queries
        for model_key in _MODELS
    ]
    results = await asyncio.gather(*tasks)

    # Group by model để tính aggregate
    by_model: dict[str, list[dict]] = {k: [] for k in _MODELS}
    per_query = []

    i = 0
    for query in queries:
        row = {"query": query[:60] + "..." if len(query) > 60 else query, "models": {}}
        for model_key in _MODELS:
            r = results[i]
            by_model[model_key].append(r)
            row["models"][model_key] = {
                "latency_ms": r["latency_ms"],
                "cost_usd": r["cost_usd"],
                "judge_score": r["judge_score"],
            }
            i += 1
        per_query.append(row)

    summary = {}
    for model_key, rows in by_model.items():
        n = len(rows)
        summary[model_key] = {
            "avg_latency_ms": round(sum(r["latency_ms"] for r in rows) / n),
            "avg_judge_score": round(sum(r["judge_score"] for r in rows) / n, 2),
            "total_cost_usd": round(sum(r["cost_usd"] for r in rows), 6),
            "total_tokens": sum(r["input_tokens"] + r["output_tokens"] for r in rows),
        }

    return {"summary": summary, "per_query": per_query}
