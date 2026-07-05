from collections import defaultdict
from dataclasses import dataclass, field


@dataclass
class LLMCall:
    agent: str
    input_tokens: int
    output_tokens: int
    latency_ms: float


@dataclass
class ToolCall:
    agent: str
    name: str
    success: bool
    latency_ms: float


@dataclass
class RoutingDecision:
    agent_chosen: str


class AITracker:
    def __init__(self):
        self.llm_calls: list[LLMCall] = []
        self.tool_calls: list[ToolCall] = []
        self.routing_decisions: list[RoutingDecision] = []

    def record_llm(self, agent: str, input_tokens: int, output_tokens: int, latency_ms: float):
        self.llm_calls.append(LLMCall(agent, input_tokens, output_tokens, latency_ms))

    def record_tool(self, agent: str, name: str, success: bool, latency_ms: float):
        self.tool_calls.append(ToolCall(agent, name, success, latency_ms))

    def record_routing(self, agent_chosen: str):
        self.routing_decisions.append(RoutingDecision(agent_chosen))

    def summary(self) -> dict:
        if not self.llm_calls:
            return {"message": "No data yet — send some requests first."}

        total_in = sum(c.input_tokens for c in self.llm_calls)
        total_out = sum(c.output_tokens for c in self.llm_calls)
        # claude-sonnet-4-6: $3/1M input tokens, $15/1M output tokens
        cost = round((total_in * 3 + total_out * 15) / 1_000_000, 4)

        latencies = sorted(c.latency_ms for c in self.llm_calls)
        p50 = round(latencies[len(latencies) // 2]) if latencies else 0
        p95 = round(latencies[int(len(latencies) * 0.95)]) if len(latencies) > 1 else p50

        routing_counts: dict[str, int] = defaultdict(int)
        for r in self.routing_decisions:
            routing_counts[r.agent_chosen] += 1

        tool_counts: dict[str, int] = defaultdict(int)
        tool_errors: dict[str, int] = defaultdict(int)
        for t in self.tool_calls:
            tool_counts[t.name] += 1
            if not t.success:
                tool_errors[t.name] += 1

        return {
            "llm_calls": len(self.llm_calls),
            "tokens": {
                "input": total_in,
                "output": total_out,
                "total": total_in + total_out,
                "estimated_cost_usd": cost,
            },
            "latency_ms": {"p50": p50, "p95": p95},
            "routing": dict(routing_counts),
            "tools": {
                name: {
                    "calls": tool_counts[name],
                    "errors": tool_errors[name],
                    "error_rate": round(tool_errors[name] / tool_counts[name], 2),
                }
                for name in tool_counts
            },
        }


_tracker = AITracker()


def get_tracker() -> AITracker:
    return _tracker
