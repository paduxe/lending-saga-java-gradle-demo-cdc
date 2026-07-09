from app.security.pii import scan


def check_output(response: str) -> dict:
    """Check LLM output for PII leakage. Returns filter result."""
    masked, findings = scan(response)

    if not findings:
        return {"clean": True, "findings": [], "response": response}

    return {
        "clean": False,
        "findings": [{"type": f.type, "masked": f.masked} for f in findings],
        "response": masked,  # trả về version đã mask
    }
