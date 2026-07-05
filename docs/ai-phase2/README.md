# Phase 2 — Multi-Agent Orchestration

## Mục tiêu

Thay vì 1 agent làm tất cả (Phase 1), Phase 2 tách thành **4 agents chuyên biệt**:
- Orchestrator phân tích intent → route sang đúng agent
- Mỗi agent chỉ có tools trong scope của nó
- OperationsAgent có confirmation guard trước write actions

---

## Architecture

```
POST /api/agent
      │
      ▼
OrchestratorAgent
  (không có tools — chỉ routing)
  (nhận full conversation history để route đúng context)
      │
      ├──▶ LoanQueryAgent          intent: query loan data
      │     tools: get_loan         "what is Loan L-001?"
      │           list_loans        "loans of CUST-100"
      │           search_loans      "show all FAILED loans"
      │
      ├──▶ SagaInvestigatorAgent   intent: diagnose failures
      │     tools: get_saga         "why did saga S-999 fail?"
      │           list_dlq          "list DLQ events"
      │
      └──▶ OperationsAgent         intent: write actions
            tools: replay_dlq       "replay DLQ event E-001"
            guard: confirm trước    → "Confirm? (yes/no)"
            khi execute             → user "yes" → execute
```

---

## File Structure

```
ai-gateway/
├── app/
│   ├── agents/
│   │   ├── base.py           ← AgentBase: system_prompt + agentic loop
│   │   ├── loan_query.py     ← LoanQueryAgent + tool definitions
│   │   ├── saga.py           ← SagaInvestigatorAgent + tool definitions
│   │   ├── operations.py     ← OperationsAgent + tool definitions
│   │   └── orchestrator.py   ← OrchestratorAgent, route() + run()
│   └── api/
│       └── agent.py          ← POST /api/agent endpoint
└── prompts/
    ├── loan_query_agent.md
    ├── saga_investigator_agent.md
    ├── operations_agent.md
    └── orchestrator_agent.md
```

---

## Endpoints

| Method | Path | Mô tả |
|---|---|---|
| POST | `/api/chat` | Phase 1 — single agent (giữ nguyên) |
| POST | `/api/agent` | Phase 2 — multi-agent với routing |

**Response `/api/agent` có thêm field `agent`:**
```json
{
  "session_id": "abc-123",
  "agent": "loan_query",
  "response": "Loan L-001 is currently FAILED..."
}
```

---

## Agent Responsibilities

### OrchestratorAgent
- Nhận full `messages` array (không chỉ tin nhắn cuối)
- Gọi Claude 1 lần với `max_tokens=128` → chỉ trả JSON routing decision
- Output format: `{"agent": "loan_query", "reason": "..."}`
- Không làm domain work — chỉ classify intent

### LoanQueryAgent
- Read-only — không có write tools
- Tools: `get_loan`, `list_loans_by_customer`, `search_loans`
- System prompt: chuyên gia query loans

### SagaInvestigatorAgent
- Read-only — không có write tools
- Tools: `get_saga`, `list_dlq_events`
- System prompt: chuyên gia diagnose saga failures + compensation flows

### OperationsAgent
- Write actions — chỉ có `replay_dlq_event`
- **Confirmation guard**: luôn hỏi "Confirm? (yes/no)" trước khi execute
- System prompt: explicit rule không replay khi chưa có explicit "yes"

---

## Sequence Diagram

```
Client          API Gateway          Orchestrator         Specialist Agent     Java Services
  │                  │                    │                      │                   │
  │  POST /api/agent │                    │                      │                   │
  │  {"message":     │                    │                      │                   │
  │  "replay E-001"} │                    │                      │                   │
  │─────────────────▶│                    │                      │                   │
  │                  │                    │                      │                   │
  │                  │ load Redis history │                      │                   │
  │                  │ build messages[]   │                      │                   │
  │                  │                    │                      │                   │
  │                  │ orchestrator.run() │                      │                   │
  │                  │───────────────────▶│                      │                   │
  │                  │                    │                      │                   │
  │                  │                    │ Claude API call      │                   │
  │                  │                    │ (routing only)       │                   │
  │                  │                    │ → {"agent":          │                   │
  │                  │                    │    "operations"}     │                   │
  │                  │                    │                      │                   │
  │                  │                    │ operations.run()     │                   │
  │                  │                    │─────────────────────▶│                   │
  │                  │                    │                      │                   │
  │                  │                    │                      │ Claude API call   │
  │                  │                    │                      │ (agentic loop)    │
  │                  │                    │                      │ → "Confirm        │
  │                  │                    │                      │    replay? yes/no"│
  │                  │                    │◀─────────────────────│                   │
  │                  │◀───────────────────│                      │                   │
  │                  │ save Redis history │                      │                   │
  │  {agent:         │                    │                      │                   │
  │   "operations",  │                    │                      │                   │
  │   response:      │                    │                      │                   │
  │   "Confirm?...}  │                    │                      │                   │
  │◀─────────────────│                    │                      │                   │
  │                  │                    │                      │                   │
  │  POST /api/agent │                    │                      │                   │
  │  X-Session-Id    │                    │                      │                   │
  │  {"message":"yes"}                    │                      │                   │
  │─────────────────▶│                    │                      │                   │
  │                  │                    │                      │                   │
  │                  │ load Redis history │                      │                   │
  │                  │ (có previous turn) │                      │                   │
  │                  │                    │                      │                   │
  │                  │ orchestrator.run() │                      │                   │
  │                  │───────────────────▶│                      │                   │
  │                  │                    │ Claude thấy history  │                   │
  │                  │                    │ "Confirm? yes/no"    │                   │
  │                  │                    │ → {"agent":          │                   │
  │                  │                    │    "operations"}     │                   │
  │                  │                    │                      │                   │
  │                  │                    │ operations.run()     │                   │
  │                  │                    │─────────────────────▶│                   │
  │                  │                    │                      │                   │
  │                  │                    │                      │ replay_dlq_event  │
  │                  │                    │                      │──────────────────▶│
  │                  │                    │                      │◀──────────────────│
  │                  │                    │                      │ "E-001 replayed"  │
  │                  │                    │◀─────────────────────│                   │
  │                  │◀───────────────────│                      │                   │
  │◀─────────────────│                    │                      │                   │
```

---

## Key Design Decisions

**Tại sao Orchestrator dùng full conversation history?**

Nếu chỉ route theo tin nhắn cuối, "yes" sẽ không có context → fall back `loan_query`. Full history cho phép Orchestrator hiểu "yes" là confirm replay từ turn trước → route đúng `operations`.

**Tại sao mỗi agent có tool set riêng?**

Giới hạn scope → giảm risk. `LoanQueryAgent` không thể replay DLQ dù bị prompt injection. `OperationsAgent` không thể query loan data ngoài nhiệm vụ.

**Tại sao Orchestrator không có tools?**

Orchestrator chỉ làm 1 việc: classify intent. Không cần tool. Giữ `max_tokens=128` vì chỉ cần JSON ngắn → tiết kiệm token, routing nhanh hơn.

---

## Demo Test Commands

```bash
# 1. Loan query → LoanQueryAgent
curl -s -X POST http://localhost:8090/api/agent \
  -H "Content-Type: application/json" \
  -d '{"message": "what is Loan L-001?"}'

# 2. Saga investigation → SagaInvestigatorAgent
curl -s -X POST http://localhost:8090/api/agent \
  -H "Content-Type: application/json" \
  -d '{"message": "list DLQ events"}'

# 3. Operation step 1 → OperationsAgent (ask confirm)
curl -s -X POST http://localhost:8090/api/agent \
  -H "Content-Type: application/json" \
  -d '{"message": "replay DLQ event E-001"}'

# 4. Operation step 2 → OperationsAgent (execute after confirm)
curl -s -X POST http://localhost:8090/api/agent \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: <session_id_from_step_3>" \
  -d '{"message": "yes"}'
```

---

## Verified Behaviors

| Test | Expected | Result |
|---|---|---|
| "what is Loan L-001?" | route → loan_query | ✓ |
| "list DLQ events" | route → saga_investigator | ✓ |
| "replay DLQ event E-001" | route → operations, ask confirm | ✓ |
| "yes" + same session | route → operations (context-aware), execute | ✓ |
| "yes" + new session | route → loan_query (no context = wrong) | expected behavior |

---

## Comparison Phase 1 vs Phase 2

| | Phase 1 `/api/chat` | Phase 2 `/api/agent` |
|---|---|---|
| Agents | 1 | 4 |
| Tool scope | Tất cả 6 tools | Mỗi agent 2-3 tools riêng |
| Write guard | Không | OperationsAgent confirm trước |
| Routing | Không | Orchestrator tự route theo intent |
| Response | session_id + response | session_id + **agent** + response |
| LLM calls/request | 2+ (agentic loop) | 1 (routing) + 2+ (specialist loop) |
