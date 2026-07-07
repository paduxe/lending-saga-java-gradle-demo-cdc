import asyncio
import time

from app.agents.orchestrator import OrchestratorAgent
from app.evals.judge import judge_response
from app.evals.retrieval import check_retrieval
from app.evals.test_cases import EVAL_CASES, EvalCase
from app.rag.store import get_store

_orchestrator = OrchestratorAgent()


async def _run_single(case: EvalCase) -> dict:
    t0 = time.monotonic()

    retrieval_result = check_retrieval(case)
    context_chunks = get_store().search(case.query, n_results=5)

    messages = [{"role": "user", "content": case.query}]
    agent_name, response = await _orchestrator.run(messages)

    scores = await judge_response(case.query, context_chunks, response)

    return {
        "id": case.id,
        "category": case.category,
        "query": case.query,
        "agent_used": agent_name,
        "response_preview": response[:200] + "..." if len(response) > 200 else response,
        "retrieval": retrieval_result,
        "scores": scores,
        "latency_ms": round((time.monotonic() - t0) * 1000),
    }


async def run_evals(case_ids: list[str] | None = None) -> dict:
    cases = EVAL_CASES if not case_ids else [c for c in EVAL_CASES if c.id in case_ids]

    results = await asyncio.gather(*[_run_single(c) for c in cases])

    passed = [r for r in results if r["scores"].get("overall", 0) >= 0.6]
    avg_retrieval = round(sum(r["retrieval"]["accuracy"] for r in results) / len(results), 2) if results else 0
    avg_overall = round(sum(r["scores"].get("overall", 0) for r in results) / len(results), 2) if results else 0

    return {
        "summary": {
            "total": len(results),
            "passed": len(passed),
            "failed": len(results) - len(passed),
            "pass_rate": round(len(passed) / len(results), 2) if results else 0,
            "avg_retrieval_accuracy": avg_retrieval,
            "avg_llm_score": avg_overall,
        },
        "results": results,
    }
