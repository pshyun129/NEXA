"""LLM 호출 클라이언트: 로컬 vLLM(OpenAI 호환)을 먼저 쓰고, 실패하면 GMS로 대체."""
import logging
from typing import Callable, TypeVar

from openai import APIConnectionError, APIStatusError, OpenAI
from pydantic import BaseModel

from core.config import settings

logger = logging.getLogger(__name__)

# 스키마로 쓸 pydantic 모델 타입 (chat_json이 받은 모델 그대로 돌려준다는 표시)
T = TypeVar("T", bound=BaseModel)

# 처음 호출할 때 한 번만 만들고 계속 재사용
_local_client: OpenAI | None = None
_gms_client: OpenAI | None = None


def _get_local_client() -> OpenAI:
    """로컬 vLLM 클라이언트. 실패하면 바로 GMS로 넘기기 위해 재시도 없음."""
    global _local_client
    if _local_client is None:
        _local_client = OpenAI(
            base_url=settings.local_llm_base_url,
            api_key=settings.local_llm_api_key,
            timeout=settings.local_llm_timeout,
            max_retries=0,
        )
    return _local_client


def _get_gms_client() -> OpenAI:
    """GMS 클라이언트. 마지막 수단이라 일시적 오류에 대비해 1회 재시도."""
    global _gms_client
    if _gms_client is None:
        _gms_client = OpenAI(
            base_url=settings.gms_base_url,
            api_key=settings.GMS_API_KEY,
            timeout=60.0,
            max_retries=1,
        )
    return _gms_client


def _local_enabled() -> bool:
    """로컬 LLM 설정이 채워져 있으면 사용."""
    return bool(settings.local_llm_base_url and settings.local_llm_api_key)


def _gms_enabled() -> bool:
    """GMS 설정이 채워져 있으면 사용."""
    return bool(settings.gms_base_url and settings.GMS_API_KEY and settings.gms_model)


def _to_developer(messages: list[dict]) -> list[dict]:
    """GMS(gpt 계열)는 system 대신 developer 역할을 쓰므로 변환."""
    return [
        {**m, "role": "developer"} if m.get("role") == "system" else m
        for m in messages
    ]


# ---------- 자유 텍스트 ----------

def _call_local(messages: list[dict], max_tokens: int, temperature: float, thinking: bool) -> str:
    """로컬 vLLM(Qwen) 호출."""
    response = _get_local_client().chat.completions.create(
        model=settings.local_llm_model,
        messages=messages,
        max_tokens=max_tokens,
        temperature=temperature,
        # vLLM 전용 옵션: 사고 모드 켜기/끄기
        extra_body={"chat_template_kwargs": {"enable_thinking": thinking}},
    )
    return response.choices[0].message.content or ""


def _call_gms(messages: list[dict], max_tokens: int) -> str:
    """GMS 호출. max_completion_tokens 사용, temperature 미지원."""
    response = _get_gms_client().chat.completions.create(
        model=settings.gms_model,
        messages=_to_developer(messages),
        max_completion_tokens=max_tokens,
    )
    return response.choices[0].message.content or ""


# ---------- 스키마 강제 출력 ----------

def _parse_local(messages: list[dict], schema: type[T], max_tokens: int, temperature: float, thinking: bool) -> T:
    """로컬 vLLM으로 스키마 강제 출력."""
    completion = _get_local_client().chat.completions.parse(
        model=settings.local_llm_model,
        messages=messages,
        response_format=schema,  # pydantic 모델 → JSON 스키마로 변환되어 전송
        max_tokens=max_tokens,
        temperature=temperature,
        extra_body={"chat_template_kwargs": {"enable_thinking": thinking}},
    )
    return _get_parsed(completion)


def _parse_gms(messages: list[dict], schema: type[T], max_tokens: int) -> T:
    """GMS로 스키마 강제 출력."""
    completion = _get_gms_client().chat.completions.parse(
        model=settings.gms_model,
        messages=_to_developer(messages),
        response_format=schema,
        max_completion_tokens=max_tokens,
    )
    return _get_parsed(completion)


def _get_parsed(completion):
    """파싱된 객체를 꺼낸다. 모델이 거부했거나 비어 있으면 에러."""
    message = completion.choices[0].message
    if message.parsed is None:
        raise ValueError(f"구조화 출력 파싱 실패 (refusal={message.refusal!r})")
    return message.parsed


# ---------- 대체 규칙 ----------

def _with_fallback(local_call: Callable[[], T], gms_call: Callable[[], T]) -> T:
    """로컬 호출을 먼저 시도하고, 연결 실패나 서버 오류면 GMS 호출로 대체한다."""
    if _local_enabled():
        try:
            return local_call()
        except APIConnectionError as e:
            # 서버 꺼짐, VPN 끊김, 시간 초과 → GMS로 대체
            logger.warning("로컬 LLM 연결 실패, GMS로 대체: %s", e)
        except APIStatusError as e:
            if e.status_code < 500:
                # 4xx는 키·요청 오류 → GMS로 넘겨도 해결 안 되므로 그대로 올림
                raise
            logger.warning("로컬 LLM 서버 오류(%s), GMS로 대체", e.status_code)

    if _gms_enabled():
        return gms_call()

    raise RuntimeError("사용 가능한 LLM이 없습니다 (로컬 실패 또는 미설정, GMS 미설정)")


# ---------- 공개 함수 ----------

def chat(messages: list[dict], *, max_tokens: int = 1024, temperature: float = 0.2, thinking: bool = False) -> str:
    """자유 텍스트 답변."""
    return _with_fallback(
        lambda: _call_local(messages, max_tokens, temperature, thinking),
        lambda: _call_gms(messages, max_tokens),
    )


def chat_json(messages: list[dict], schema: type[T], *, max_tokens: int = 1024, temperature: float = 0.0, thinking: bool = False) -> T:
    """스키마(pydantic 모델)에 맞는 객체로 답변. 추출·분류 노드용."""
    return _with_fallback(
        lambda: _parse_local(messages, schema, max_tokens, temperature, thinking),
        lambda: _parse_gms(messages, schema, max_tokens),
    )

# ---------- 상태 확인 ----------

def local_status() -> str:
    """로컬 LLM 상태: up / down / disabled."""
    if not _local_enabled():
        return "disabled"
    try:
        # 모델 목록 조회로 생존 확인. 상태 확인은 빨리 끝나야 하므로 3초만 기다림
        _get_local_client().with_options(timeout=3.0).models.list()
        return "up"
    except (APIConnectionError, APIStatusError):
        return "down"


def gms_status() -> str:
    """GMS 설정 상태: configured / disabled (실제 호출은 하지 않음 → 비용 없음)."""
    return "configured" if _gms_enabled() else "disabled"