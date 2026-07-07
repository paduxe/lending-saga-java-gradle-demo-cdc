from app.evals.test_cases import EvalCase
from app.rag.store import get_store


def check_retrieval(case: EvalCase, n_results: int = 5) -> dict:
    """Check whether expected keywords appear in RAG retrieved chunks."""
    chunks = get_store().search(case.query, n_results=n_results)
    combined_text = " ".join(c["text"] for c in chunks).lower()

    hits = [kw for kw in case.expected_keywords if kw.lower() in combined_text]
    misses = [kw for kw in case.expected_keywords if kw.lower() not in combined_text]

    return {
        "chunks_retrieved": len(chunks),
        "keywords_expected": case.expected_keywords,
        "keywords_found": hits,
        "keywords_missing": misses,
        "accuracy": round(len(hits) / len(case.expected_keywords), 2) if case.expected_keywords else 1.0,
        "sources": list({c["source"] for c in chunks}),
    }
