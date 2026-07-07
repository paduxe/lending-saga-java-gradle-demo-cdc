from fastapi import APIRouter
from pydantic import BaseModel

from app.evals.runner import run_evals
from app.evals.test_cases import EVAL_CASES

router = APIRouter(prefix="/evals")


class EvalRequest(BaseModel):
    case_ids: list[str] | None = None  # None = run all cases


@router.get("/cases")
async def list_cases():
    return [{"id": c.id, "query": c.query, "category": c.category} for c in EVAL_CASES]


@router.post("/run")
async def run(request: EvalRequest = EvalRequest()):
    return await run_evals(request.case_ids)
