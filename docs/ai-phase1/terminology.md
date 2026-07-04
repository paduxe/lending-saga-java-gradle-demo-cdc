# AI Gateway — Terminology

## LLM (Large Language Model)

Model ngôn ngữ được train trên lượng lớn text data. Kiến thức "đóng băng" tại thời điểm training — không biết live data, không browse internet khi inference.

**Trong project:** Claude claude-sonnet-4-6 của Anthropic.

---

## Tool Calling / Function Calling

Cơ chế cho phép LLM **yêu cầu** chương trình bên ngoài thực thi một hành động, sau đó nhận kết quả để tiếp tục reasoning.

- LLM không tự gọi — chỉ ra lệnh bằng text `{ "tool_use": "get_loan", "input": {...} }`
- Code (executor.py) mới là người thực sự gọi HTTP
- Anthropic không có network access tới hệ thống của bạn

```
Claude → "gọi get_loan với loan_id=L-001"
executor.py → HTTP GET loan-service:8081/...
Kết quả → feed lại Claude
```

---

## Agentic Loop

Kỹ thuật xử lý trong code — `while True` loop gọi LLM lặp lại cho đến khi LLM trả về `end_turn`.

```python
while True:
    response = await llm.call(messages)
    if response.stop_reason == "end_turn":   # Claude đủ thông tin
        return response.text
    if response.stop_reason == "tool_use":   # Claude cần thêm data
        result = execute_tool(response.tool)
        messages.append(result)              # feed lại → loop tiếp
```

Cho phép Claude **tự quyết định** cần bao nhiêu tool calls — code không hard-code flow.

---

## Reasoning

Khả năng suy luận của LLM — từ data đã có, rút ra kết luận mới mà không cần tra cứu thêm.

```
Tool trả về: { saga_state: "CREDIT_REQUESTED", created_at: "10:00", now: "11:30" }
Claude reasoning: "90 phút > timeout 60s → saga bị stuck → check DLQ"
```

Không phải tra Google — suy luận từ data + kiến thức training.

---

## Live Data

Data phản ánh **trạng thái thực tế hiện tại** của hệ thống, lấy từ database/API thực — đối lập với static knowledge trong model weights.

LLM chỉ có live data thông qua tool calling.

---

## Grounding

Kết nối LLM với real-world data để tránh hallucination. Tool calling là một hình thức grounding.

```
Không grounding: Claude bịa status của Loan L-001
Có grounding:    Claude gọi tool → lấy status thực từ DB
```

---

## System Prompt

Text inject vào mỗi LLM API call để định nghĩa persona, behavior rules, và domain context. User không thấy, ai-gateway tự inject.

**Cấu trúc chuẩn:**
1. Identity — Claude là ai
2. Role — làm gì cho ai
3. Capabilities — tools nào có
4. Behavior Rules — được/không được làm gì
5. Domain Context — kiến thức về hệ thống

---

## Prompt Engineering

Kỹ năng thiết kế system prompt và tool descriptions để điều khiển LLM behavior. Description càng rõ → Claude chọn đúng tool → kết quả càng chính xác.

---

## Conversation Memory

Cơ chế lưu lịch sử chat để hỗ trợ multi-turn conversation. Trong project dùng Redis.

Anthropic API stateless — mỗi call phải gửi toàn bộ history. Redis lưu history để ai-gateway build lại `messages` array mỗi request.

---

## ReAct Pattern (Reasoning + Acting)

Pattern thiết kế cho AI Agent:
- **Reasoning:** LLM suy luận cần làm gì
- **Acting:** Code thực thi hành động
- Lặp lại cho đến khi có đủ thông tin

Agentic loop trong project là implementation của ReAct pattern.

---

## AI Agent vs LLM

| | LLM thuần | AI Agent |
|---|---|---|
| Input/Output | Text → Text | Text → Action → Text |
| Tool access | Không | Có |
| Multi-step | Không | Có (agentic loop) |
| Live data | Không | Có (qua tools) |

ai-gateway là AI Agent vì có tool calling + agentic loop.

---

## Hallucination

LLM trả về thông tin sai/bịa đặt một cách tự tin. Giảm thiểu bằng:
- Grounding qua tool calling
- System prompt rule: "never fabricate loan IDs"
- Graceful error handling khi tool fail

---

## SSE (Server-Sent Events)

Protocol cho phép server push data về client theo từng chunk — dùng cho streaming response của LLM thay vì đợi toàn bộ response xong mới trả về.

```
Client ← "Loan..." ← "L-001..." ← "is FAILED..." ← [DONE]
         chunk 1       chunk 2        chunk 3
```
