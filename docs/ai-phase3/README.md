# Phase 3 — RAG + AI Observability

## Mục tiêu

- **RAG**: LLM truy xuất knowledge từ tài liệu nội bộ (CLAUDE.md, docs/) thay vì chỉ dựa vào training data
- **Observability**: Monitor AI system trong production — token usage, cost, latency, tool calls, routing

---

## Part A — RAG (Retrieval-Augmented Generation)

### Vấn đề Phase 2 giải quyết

```
Không có RAG:
User: "fraud score threshold là bao nhiêu?"
Claude: hallucinate hoặc trả lời chung chung

Có RAG:
User: "fraud score threshold là bao nhiêu?"
→ search_knowledge_base → tìm trong CLAUDE.md
→ "reject ngay nếu fraud score ≥ 100" (EasyRules)
→ Claude trả lời chính xác từ tài liệu thực
```

### File Structure

```
app/rag/
├── loader.py    ← đọc CLAUDE.md + docs/**/*.md → split thành chunks
└── store.py     ← ChromaDB + sentence-transformers, build_index() + search()
```

### Cách hoạt động

```
Server startup (1 lần):
CLAUDE.md + docs/*.md
    │ loader.py đọc file → split 250 words/chunk, overlap 40 words
    ▼
ChromaDB
    │ sentence-transformers (all-MiniLM-L6-v2) embed mỗi chunk → vector
    │ lưu vector + text + source vào collection "lending_knowledge"
    ▼
Ready để search

Per request (khi Claude gọi tool):
User message
    │ Claude quyết định cần search docs
    ▼
search_knowledge_base(query="fraud rules")
    │ executor.py → ChromaDB.query(query_texts=[query])
    │ semantic search: tìm top-3 chunks gần nhất về nghĩa
    ▼
text chunks → nhét vào messages[] như tool_result
    │
    ▼
Claude đọc → tổng hợp → trả lời chính xác
```

### Tool Definition

```python
# Inject vào tất cả agents trong base.py
_RAG_TOOL = {
    "name": "search_knowledge_base",
    "description": "Search platform's internal documentation. Use for business rules, architecture, configuration questions.",
    "input_schema": {
        "properties": {
            "query": {"type": "string"},
            "n_results": {"type": "integer"}  # default 3
        },
        "required": ["query"]
    }
}
```

### Documents được index

| File | Nội dung |
|---|---|
| `CLAUDE.md` | Architecture, commands, Saga states, business rules, tech stack |
| `docs/ai-phase1/README.md` | AI Gateway Phase 1 architecture |
| `docs/ai-phase1/terminology.md` | AI terms và concepts |
| `docs/ai-phase2/README.md` | Multi-Agent architecture |

### Tech Stack

| Component | Thư viện | Vai trò |
|---|---|---|
| Vector store | `chromadb>=0.5.0` | Lưu và tìm kiếm vectors |
| Embeddings | `sentence-transformers>=2.7.0` | Convert text → vector (local, free) |
| Model | `all-MiniLM-L6-v2` | 80MB, nhanh, chất lượng tốt |

### CLAUDE.md — 2 vai trò

```
CLAUDE.md
    │
    ├── Claude Code CLI: đọc lúc start conversation
    │       → project instructions cho developer
    │
    └── ai-gateway RAG: đọc lúc server startup
            → knowledge source cho LLM
```

Cùng 1 file, 2 consumer khác nhau, hoàn toàn độc lập.

---

## Part B — AI Observability

### File Structure

```
app/observability/
└── tracker.py    ← AITracker singleton: record + aggregate metrics

app/api/
└── metrics.py    ← GET /api/metrics/ai
```

### Metrics được track

| Metric | Mô tả |
|---|---|
| `llm_calls` | Tổng số lần gọi Anthropic API |
| `tokens.input` | Tổng input tokens |
| `tokens.output` | Tổng output tokens |
| `tokens.estimated_cost_usd` | Ước tính chi phí ($3/1M input, $15/1M output) |
| `latency_ms.p50/p95` | Percentile latency của LLM calls |
| `routing` | Phân phối requests theo agent |
| `tools.{name}.calls` | Số lần mỗi tool được gọi |
| `tools.{name}.error_rate` | Tỷ lệ lỗi mỗi tool |

### Sample Response

```json
GET /api/metrics/ai
{
  "llm_calls": 2,
  "tokens": {
    "input": 6209,
    "output": 517,
    "total": 6726,
    "estimated_cost_usd": 0.0264
  },
  "latency_ms": { "p50": 10009, "p95": 10009 },
  "routing": { "loan_query": 1 },
  "tools": {
    "search_knowledge_base": { "calls": 1, "errors": 0, "error_rate": 0.0 }
  }
}
```

### Wiring

```
AgentBase.run()
    ├── record_llm(agent, input_tokens, output_tokens, latency_ms)
    └── record_tool(agent, name, success, latency_ms)

OrchestratorAgent.route()
    └── record_routing(agent_chosen)
```

Tracker là singleton in-memory — reset khi restart server. Production sẽ dùng Prometheus/InfluxDB.

---

## Endpoints Phase 3

| Method | Path | Mô tả |
|---|---|---|
| POST | `/api/chat` | Phase 1 — single agent |
| POST | `/api/agent` | Phase 2 — multi-agent |
| GET | `/api/metrics/ai` | Phase 3 — AI observability |

---

## Demo Test

```bash
# RAG — hỏi về business rules (không có trong system prompt)
curl -s -X POST http://localhost:8090/api/agent \
  -H "Content-Type: application/json" \
  -d '{"message": "fraud score threshold của platform là bao nhiêu?"}'

# RAG — hỏi về architecture
curl -s -X POST http://localhost:8090/api/agent \
  -H "Content-Type: application/json" \
  -d '{"message": "saga timeout là bao nhiêu giây?"}'

# Observability
curl -s http://localhost:8090/api/metrics/ai
```

---

## Verified Behaviors

| Test | Expected | Result |
|---|---|---|
| Hỏi fraud threshold | RAG tìm CLAUDE.md, trả "≥ 100" | ✓ |
| GET /api/metrics/ai | JSON với tokens, cost, routing, tools | ✓ |
| Tool tracking | search_knowledge_base calls được đếm | ✓ |
| Cost tracking | estimated_cost_usd được tính | ✓ |

---

## So sánh 3 Phases

| | Phase 1 | Phase 2 | Phase 3 |
|---|---|---|---|
| Knowledge | System prompt (static) | System prompt | + RAG từ docs (dynamic) |
| Agents | 1 | 4 | 4 + RAG tool cho tất cả |
| Observability | Không | Không | Token, cost, latency, routing |
| Endpoints | `/api/chat` | + `/api/agent` | + `/api/metrics/ai` |
| New tech | — | — | ChromaDB + sentence-transformers |
