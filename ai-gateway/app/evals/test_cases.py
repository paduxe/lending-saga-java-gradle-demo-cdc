from dataclasses import dataclass


@dataclass
class EvalCase:
    id: str
    query: str
    expected_keywords: list[str]  # must appear in RAG retrieved chunks
    category: str                 # "architecture" | "business_rule" | "operations"


EVAL_CASES: list[EvalCase] = [
    EvalCase(
        id="fraud-threshold",
        query="What is the fraud score threshold to block a loan?",
        expected_keywords=["fraud score", "100"],
        category="business_rule",
    ),
    EvalCase(
        id="kafka-topics",
        query="What Kafka topics does the lending system use?",
        expected_keywords=["saga-events", "saga-commands"],
        category="architecture",
    ),
    EvalCase(
        id="outbox-pattern",
        query="How does the transactional outbox pattern work in this system?",
        expected_keywords=["outbox_event", "Debezium"],
        category="architecture",
    ),
    EvalCase(
        id="saga-states",
        query="What are the states in the saga state machine?",
        expected_keywords=["KYC_REQUESTED", "COMPLETED", "FAILED"],
        category="architecture",
    ),
    EvalCase(
        id="db-sharding",
        query="How does database sharding work in the loan service?",
        expected_keywords=["ShardRoutingDataSource", "hashCode"],
        category="architecture",
    ),
    EvalCase(
        id="cache-strategy",
        query="What caching strategy is used for saga state?",
        expected_keywords=["write-through", "Redis"],
        category="architecture",
    ),
    EvalCase(
        id="idempotency",
        query="How does the system prevent duplicate Kafka message processing?",
        expected_keywords=["SETNX", "idempotency"],
        category="operations",
    ),
]
