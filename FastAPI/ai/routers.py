from fastapi import APIRouter

from ai import llm, ocr
from ai.schemas import LlmChatRequest, LlmChatResponse, LlmHealthResponse
from core.response import ApiResult, ok

router = APIRouter(prefix="/llm", tags=["LLM"])


@router.get("/health", response_model=ApiResult[LlmHealthResponse])
def llm_health():
    """로컬 LLM 생존 여부와 GMS 설정 여부."""
    return ok({"local": llm.local_status(), "gms": llm.gms_status(), "ocr": ocr.ocr_status()})


@router.post("/chat", response_model=ApiResult[LlmChatResponse])
def llm_chat(req: LlmChatRequest):
    """개발용: 질문 하나를 보내고 답변을 받는다 (로컬 → 실패 시 GMS)."""
    answer = llm.chat(
        [{"role": "user", "content": req.prompt}],
        max_tokens=req.max_tokens,
        thinking=req.thinking,
    )
    return ok({"answer": answer})