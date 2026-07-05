from pathlib import Path

_CHUNK_SIZE = 250  # words per chunk
_OVERLAP = 40      # words overlap between chunks


def load_documents(root: Path) -> list[dict]:
    """Load .md files from root and docs/ subdirectory, split into chunks."""
    sources = [
        root / "CLAUDE.md",
        *(root / "docs").glob("**/*.md"),
    ]
    chunks = []
    for path in sources:
        if not path.exists():
            continue
        text = path.read_text(encoding="utf-8")
        relative = path.relative_to(root)
        for i, chunk_text in enumerate(_split(text)):
            chunks.append({
                "id": f"{relative}__chunk_{i}",
                "text": chunk_text,
                "source": str(relative),
            })
    return chunks


def _split(text: str) -> list[str]:
    words = text.split()
    chunks = []
    start = 0
    while start < len(words):
        end = min(start + _CHUNK_SIZE, len(words))
        chunks.append(" ".join(words[start:end]))
        start += _CHUNK_SIZE - _OVERLAP
    return chunks
