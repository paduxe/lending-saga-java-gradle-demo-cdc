from fastapi import APIRouter
from pydantic import BaseModel

from app.security.pii import scan

router = APIRouter(prefix="/security")


class ScanRequest(BaseModel):
    text: str


@router.post("/scan")
async def pii_scan(body: ScanRequest):
    masked, findings = scan(body.text)
    return {
        "has_pii": len(findings) > 0,
        "findings": [{"type": f.type, "masked": f.masked} for f in findings],
        "original_length": len(body.text),
        "masked_text": masked,
    }
