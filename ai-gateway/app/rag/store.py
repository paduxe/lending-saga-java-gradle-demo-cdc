from pathlib import Path

import chromadb
from chromadb.utils.embedding_functions import SentenceTransformerEmbeddingFunction

from app.rag.loader import load_documents

_PROJECT_ROOT = Path(__file__).parent.parent.parent.parent
_MODEL = "all-MiniLM-L6-v2"  # 80MB, fast, good quality


class KnowledgeStore:
    def __init__(self):
        self._client = chromadb.Client()
        self._ef = SentenceTransformerEmbeddingFunction(model_name=_MODEL)
        self._collection = self._client.get_or_create_collection(
            name="lending_knowledge",
            embedding_function=self._ef,
        )
        self._ready = False

    def build_index(self) -> int:
        chunks = load_documents(_PROJECT_ROOT)
        if not chunks:
            return 0
        self._collection.add(
            ids=[c["id"] for c in chunks],
            documents=[c["text"] for c in chunks],
            metadatas=[{"source": c["source"]} for c in chunks],
        )
        self._ready = True
        return len(chunks)

    def search(self, query: str, n_results: int = 3) -> list[dict]:
        if not self._ready:
            return []
        results = self._collection.query(
            query_texts=[query],
            n_results=n_results,
        )
        return [
            {"text": doc, "source": meta["source"]}
            for doc, meta in zip(results["documents"][0], results["metadatas"][0])
        ]


_store = KnowledgeStore()


def get_store() -> KnowledgeStore:
    return _store
