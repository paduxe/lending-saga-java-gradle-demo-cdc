import re
from dataclasses import dataclass


@dataclass
class PIIFinding:
    type: str
    masked: str      # value đã mask, không lưu value gốc
    start: int
    end: int


# Patterns theo thứ tự ưu tiên (dài trước để tránh partial match)
_PATTERNS: list[tuple[str, re.Pattern]] = [
    ("CREDIT_CARD",  re.compile(r"\b\d{4}[\s-]?\d{4}[\s-]?\d{4}[\s-]?\d{4}\b")),
    ("VN_CCCD",      re.compile(r"\b\d{12}\b")),                                      # CCCD 12 số
    ("VN_OLD_ID",    re.compile(r"\b\d{9}\b")),                                       # CMND 9 số
    ("VN_PHONE",     re.compile(r"(?<!\d)(0|\+84)(3[2-9]|5[6-9]|7[06-9]|8[0-9]|9[0-9])\d{7}(?!\d)")),
    ("EMAIL",        re.compile(r"[\w.+-]+@[\w-]+\.[\w.]+")),
]

_MASK = {
    "CREDIT_CARD": "****-****-****-****",
    "VN_CCCD":     "************",
    "VN_OLD_ID":   "*********",
    "VN_PHONE":    "***********",
    "EMAIL":       "***@***.***",
}


def scan(text: str) -> tuple[str, list[PIIFinding]]:
    """Scan text for PII. Returns (masked_text, findings)."""
    findings: list[PIIFinding] = []
    masked = text

    # Track replaced ranges để tránh double-replace
    replaced: list[tuple[int, int]] = []

    for pii_type, pattern in _PATTERNS:
        for m in pattern.finditer(text):
            if any(s <= m.start() < e or s < m.end() <= e for s, e in replaced):
                continue
            findings.append(PIIFinding(
                type=pii_type,
                masked=_MASK[pii_type],
                start=m.start(),
                end=m.end(),
            ))
            replaced.append((m.start(), m.end()))

    # Replace từ cuối về đầu để giữ đúng index
    for f in sorted(findings, key=lambda x: x.start, reverse=True):
        masked = masked[: f.start] + f.masked + masked[f.end :]

    return masked, findings


def has_pii(text: str) -> bool:
    _, findings = scan(text)
    return len(findings) > 0
