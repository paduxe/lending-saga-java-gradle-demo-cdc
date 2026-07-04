# Phase 1 — AI Gateway (LLM Tool Calling Agent)

## Mục tiêu

Xây dựng AI Gateway kết nối Claude (LLM) với lending platform microservices, cho phép operators query live data bằng ngôn ngữ tự nhiên thay vì phải truy cập trực tiếp Elasticsearch, Kibana hay viết query thủ công.

---

## Output Phase 1

```
ai-gateway/
├── app/
│   ├── main.py                  ← FastAPI app, mount /api/*
│   ├── config.py                ← đọc ANTHROPIC_API_KEY từ .env
│   ├── api/
│   │   └── chat.py              ← POST /api/chat + /api/chat/stream
│   ├── tools/
│   │   ├── definitions.py       ← 6 tool definitions khai báo cho Claude
│   │   └── executor.py          ← gọi HTTP tới Java services
│   └── memory/
│       └── conversation.py      ← lưu history vào Redis
└── prompts/
    └── system_prompt.md         ← persona + behavior rules của LLM
```

### Endpoints

| Method | Path | Mô tả |
|---|---|---|
| POST | `/api/chat` | Chat đồng bộ, trả JSON |
| POST | `/api/chat/stream` | Chat streaming SSE |
| GET | `/health` | Health check |

### Tools được định nghĩa

| Tool | Java Endpoint | Mô tả |
|---|---|---|
| `get_loan` | `GET loan-service:8081/api/queries/loans/{id}` | Query 1 loan theo ID |
| `get_saga` | `GET orchestrator:8080/api/sagas/{id}` | Track saga lifecycle |
| `list_loans_by_customer` | `GET loan-service:8081/api/queries/loans/customer/{id}` | Loans theo customer |
| `search_loans` | `GET loan-service:8081/api/queries/loans/status/{status}` | Search theo status |
| `list_dlq_events` | `GET orchestrator:8080/api/dlq` | DLQ events |
| `replay_dlq_event` | `POST orchestrator:8080/api/dlq/{id}/replay` | Replay DLQ event |

---

## Architecture

### Flow đầy đủ

```
Client
  │
  POST /api/chat { "message": "what is Loan L-001?" }
  │
  ▼
AI Gateway (FastAPI :8090)
  │
  ├── load_history(session_id) → Redis
  │
  ├── AGENTIC LOOP:
  │     │
  │     ├── [Call 1] Anthropic API
  │     │     Input:  system_prompt + tools + messages
  │     │     Output: stop_reason="tool_use"
  │     │             { name: "get_loan", input: {loan_id: "L-001"} }
  │     │
  │     ├── executor.py → HTTP GET loan-service:8081/api/queries/loans/L-001
  │     │     → { status: "FAILED", amount: 1000, ... }
  │     │
  │     └── [Call 2] Anthropic API
  │           Input:  + tool_result { status: "FAILED", ... }
  │           Output: stop_reason="end_turn"
  │                   "Loan L-001 is FAILED at CREDIT step..."
  │
  ├── append_turn(session_id) → Redis
  │
  └── { session_id, response: "Loan L-001 is FAILED..." }
        │
        ▼
      Client
```

### Layer responsibilities

| Layer | Trách nhiệm | Không biết gì |
|---|---|---|
| AI Gateway | Nhận request, quản lý session, điều phối agentic loop, gọi Java services | Claude hoạt động thế nào |
| Claude (Anthropic) | Hiểu ngôn ngữ tự nhiên, reasoning, quyết định tool nào cần dùng | Java services tồn tại, HTTP calls |
| Java Services | Business logic, query DB, trả JSON | AI tồn tại |

---

## Tech Stack

| Component | Technology |
|---|---|
| Web framework | FastAPI + Uvicorn (ASGI) |
| LLM | Claude claude-sonnet-4-6 (Anthropic) |
| LLM SDK | `anthropic>=0.40.0` |
| Conversation memory | Redis (TTL 1h, max 20 turns) |
| HTTP client | `httpx` async |
| Config | `pydantic-settings` + `.env` |
| Streaming | SSE (Server-Sent Events) |

---

## Khởi chạy local

```bash
# 1. Start Redis
docker run -d --name demo-redis -p 6379:6379 redis:7-alpine

# 2. Install dependencies
python -m venv .venv
.venv/bin/pip install -r requirements.txt

# 3. Config .env
ANTHROPIC_API_KEY=sk-ant-api03-...
CLAUDE_MODEL=claude-sonnet-4-6
REDIS_URL=redis://localhost:6379

# 4. Start server
.venv/bin/python -m uvicorn app.main:app --host 0.0.0.0 --port 8090

# 5. Test
curl -X POST http://localhost:8090/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "What happens when a saga fails at the CREDIT step?"}'
```

---

## Key Design Decisions

**Tại sao không query DB trực tiếp?**
- Bypass business logic (CryptoConverter, AuditLogAspect, RateLimit)
- Không có audit trail
- Scope không kiểm soát được
- Java service là single source of truth

**Tại sao Redis cho conversation memory?**
- Anthropic API stateless — không lưu session
- Client không cần gửi lại toàn bộ history mỗi request
- TTL 1h tự động expire
- Nếu Redis down → LLM vẫn hoạt động, chỉ mất multi-turn context

**Tại sao agentic loop thay vì single call?**
- Cho phép Claude chain nhiều tool calls trong 1 request
- Claude tự quyết định cần data gì — không hard-code flow
- Graceful handling khi tool fail

---

## Limitations

- Không đảm bảo 100% accuracy — Claude có thể interpret sai kết quả
- Dùng cho investigation/monitoring, không phải audit/compliance
- Tool calling chỉ hoạt động khi Java services đang chạy
- System prompt cache in-memory — cần restart server khi update prompt
