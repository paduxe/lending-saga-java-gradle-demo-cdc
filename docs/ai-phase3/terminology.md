# Phase 3 — Terminology

## RAG (Retrieval-Augmented Generation)

Kỹ thuật bổ sung knowledge động cho LLM bằng cách tìm kiếm tài liệu liên quan trước khi hỏi.

```
Không có RAG:  LLM trả lời từ training data → có thể sai/thiếu
Có RAG:        Tìm docs liên quan → nhét vào context → LLM trả lời chính xác
```

RAG = Tool Calling + Vector Database. Không có gì magic hơn ngoài 2 thứ đó.

---

## Vector / Embedding

Chuyển text thành dãy số (vector) để so sánh ngữ nghĩa.

```
"loan bị failed"   → [0.2, 0.8, 0.1, ...]
"saga thất bại"    → [0.2, 0.7, 0.1, ...]  ← gần → tìm được
"weather forecast" → [0.9, 0.1, 0.7, ...]  ← xa  → không tìm
```

Hai câu có nghĩa giống nhau → vector gần nhau → semantic search tìm được dù không trùng keyword.

---

## Vector Store (ChromaDB)

Database chuyên lưu và tìm kiếm vectors theo khoảng cách ngữ nghĩa.

```
PostgreSQL:  lưu rows → tìm bằng WHERE, LIKE
ChromaDB:    lưu vectors → tìm bằng "gần nhất về nghĩa"
```

Trong project: ChromaDB chạy in-process (không cần server riêng). Tương đương H2 in-memory của Spring Boot test.

---

## Chunks

Cắt tài liệu dài thành đoạn nhỏ để embed và tìm kiếm hiệu quả.

```
CLAUDE.md (dài)
    │ split 250 words/chunk, overlap 40 words
    ▼
chunk_0: "Transactional Outbox + CDC..."
chunk_1: "Saga State Machine: INIT → KYC..."
chunk_2: "Anti-Fraud Engine: reject nếu score ≥ 100..."
```

Overlap 40 words đảm bảo không mất context ở ranh giới giữa 2 chunks.

---

## Sentence-Transformers

Thư viện Python tạo embeddings locally — không cần gọi API, không tốn tiền.

```
Model: all-MiniLM-L6-v2
Size:  ~80MB (download lần đầu)
Speed: local, nhanh
Cost:  free
```

Thay thế: Anthropic Embeddings API (không cần download model nhưng tốn API credit).

---

## Semantic Search

Tìm kiếm dựa trên ngữ nghĩa, không phải keyword.

```
Keyword search (SQL LIKE):
  "fraud score threshold" → chỉ tìm được đúng từ này

Semantic search (vector):
  "fraud score threshold" → tìm được cả:
  - "reject ngay nếu fraud score ≥ 100"
  - "điểm gian lận vượt ngưỡng"
  - "anti-fraud engine block request"
```

---

## AI Observability

Monitor AI system như monitor production app — nhưng track AI-specific metrics.

```
APM thường:    HTTP latency, error rate, throughput
AI Observability thêm:
  - Token usage: bao nhiêu tokens input/output
  - Cost:        bao nhiêu tiền mỗi request
  - Tool calls:  tool nào được gọi, tool nào lỗi
  - Routing:     agent nào xử lý bao nhiêu % requests
  - LLM latency: Anthropic API response time
```

---

## Token

Đơn vị tính chi phí và kích thước của LLM.

```
~1 token ≈ 0.75 words (tiếng Anh)
~1 token ≈ 0.5 words (tiếng Việt — nhiều token hơn)

Claude Sonnet 4.6 pricing:
  Input:  $3 / 1 triệu tokens
  Output: $15 / 1 triệu tokens
```

Mỗi request gồm: system_prompt + history + user message + tool_results = tổng input tokens.

---

## CLAUDE.md — 2 vai trò

```
CLAUDE.md
    │
    ├── VAI TRÒ 1: Claude Code CLI
    │       Đọc lúc: start conversation
    │       Mục đích: project instructions cho developer
    │
    └── VAI TRÒ 2: ai-gateway RAG
            Đọc lúc: server startup (loader.py)
            Mục đích: knowledge source → index vào ChromaDB
```

Cùng 1 file, 2 consumer hoàn toàn độc lập.

---

## Claude Memory vs RAG vs Redis

```
Claude Code memory (memory/*.md):
  → Claude Code CLI lưu/đọc giữa các conversations
  → Nhớ user profile, project context, feedback

Redis (ai-gateway):
  → Lưu conversation history trong 1 session
  → Anthropic API stateless → cần Redis build lại messages[]

ChromaDB RAG (ai-gateway):
  → Index từ CLAUDE.md + docs khi server startup
  → Claude query khi cần kiến thức về platform
  → Không thay đổi khi server chạy
```

Ba thứ hoàn toàn độc lập, phục vụ mục đích khác nhau.

---

## Startup Index vs Runtime Search

```
Startup (1 lần, chậm ~vài giây):
  CLAUDE.md → read → split → embed → ChromaDB

Runtime (mỗi request, nhanh ~ms):
  query → embed query → ChromaDB.search() → top-3 chunks
```

Giống cách database đánh index 1 lần → query nhanh về sau.
