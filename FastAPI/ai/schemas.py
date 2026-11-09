from core.schemas import CamelModel


class LlmChatRequest(CamelModel):
    prompt: str                 # 보낼 질문
    max_tokens: int = 256       # 답변 최대 길이 (JSON에서는 maxTokens)
    thinking: bool = False      # 사고 모드 (로컬에서만 적용)


class LlmChatResponse(CamelModel):
    answer: str                 # 답변 텍스트


class LlmHealthResponse(CamelModel):
    local: str                  # up / down / disabled
    gms: str                    # configured / disabled
    ocr: str                    # up / down / disabled