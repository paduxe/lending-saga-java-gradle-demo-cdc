import json
import re

import anthropic

from app.config import settings

_client = anthropic.AsyncAnthropic(api_key=settings.anthropic_api_key)

# Use Haiku for judging: cheap, fast, good enough for scoring
_JUDGE_MODEL = "claude-haiku-4-5-20251001"

_SYSTEM = """You are an evaluation judge for an AI assistant used in a lending platform.
Score the assistant's response on 3 dimensions (0–5 each):

- faithfulness: Is the answer grounded in the provided context OR explicitly cited system knowledge? 0=hallucinated facts, 5=fully grounded
- relevance: Does the answer address the question? 0=off-topic, 5=directly answers the question
- groundedness: Are specific claims backed by the retrieved context OR by the agent citing its own system definition? 0=none, 5=all claims traceable

Important: If the response says "according to the saga state machine defined in this system" or similar phrases indicating it cites its own pre-defined system knowledge, treat that as a valid grounding source — not hallucination.

Return ONLY a single valid JSON object with no extra text:
{"faithfulness": <int>, "relevance": <int>, "groundedness": <int>, "reasoning": "<one sentence>"}"""


def _extract_json(raw: str) -> dict:
    match = re.search(r"\{[^{}]+\}", raw, re.DOTALL)
    if match:
        return json.loads(match.group())
    raise ValueError("no JSON object found")


async def judge_response(query: str, context_chunks: list[dict], response: str) -> dict:
    context_text = "\n---\n".join(c["text"] for c in context_chunks)

    user_message = f"""Question: {query}

Retrieved context:
{context_text}

Assistant response:
{response}

Score the response."""

    result = await _client.messages.create(
        model=_JUDGE_MODEL,
        max_tokens=256,
        system=_SYSTEM,
        messages=[{"role": "user", "content": user_message}],
    )
    raw = "".join(b.text for b in result.content if hasattr(b, "text")).strip()
    try:
        scores = _extract_json(raw)
        scores["overall"] = round(
            (scores.get("faithfulness", 0) + scores.get("relevance", 0) + scores.get("groundedness", 0)) / 15, 2
        )
        return scores
    except (json.JSONDecodeError, ValueError, KeyError):
        return {"faithfulness": 0, "relevance": 0, "groundedness": 0, "overall": 0.0, "reasoning": "judge parse error", "raw": raw}
