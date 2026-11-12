"""DSPy용 LM 설정: 로컬 vLLM을 먼저 쓰고, 실패하면 GMS로 다시 실행."""
import logging
import threading

import dspy
# DSPy는 LiteLLM 에러를 자기 에러로 감싸서 던지므로 DSPy 에러로 잡아야 함
from dspy.utils.exceptions import LMServerError, LMTimeoutError, LMTransportError

from ai.llm import _gms_enabled, _local_enabled  # 설정 확인은 llm.py와 같은 기준 사용
from core.config import settings
from core.metrics import LLM_CALLS

logger = logging.getLogger(__name__)

# 처음 쓸 때 한 번만 만들고 재사용 (로컬은 모델 이름별: 기본 모델 + 승격된 LoRA 어댑터)
_local_lms: dict[str, dspy.LM] = {}
_gms_lm: dspy.LM | None = None


def get_local_lm(model: str | None = None) -> dspy.LM:
    """로컬 vLLM(Qwen)용 DSPy LM. model = vLLM에 띄운 다른 모델 이름(LoRA 어댑터), 생략하면 기본 모델"""
    name = model or settings.local_llm_model
    lm = _local_lms.get(name)
    if lm is None:
        lm = dspy.LM(
            model=f"openai/{name}",                      # "openai/" = OpenAI 호환 서버라는 LiteLLM 표기
            api_base=settings.local_llm_base_url,        # GPU 서버 vLLM 주소 (끝에 /v1)
            api_key=settings.local_llm_api_key,
            temperature=0.0,                             # 추출·분류용이라 항상 같은 답
            max_tokens=1024,
            timeout=settings.local_llm_timeout,
            num_retries=0,                               # 실패하면 바로 GMS로 넘기기 위해 재시도 없음
            cache=False,                                 # 지금은 대체 동작 확인을 위해 캐시 끔 (최적화 단계에서 다시 켬)
            # vLLM 전용 옵션: 사고 모드 끄기
            extra_body={"chat_template_kwargs": {"enable_thinking": False}},
        )
        _local_lms[name] = lm
    return lm


def get_gms_lm() -> dspy.LM:
    """GMS용 DSPy LM."""
    global _gms_lm
    if _gms_lm is None:
        _gms_lm = dspy.LM(
            model=f"openai/{settings.gms_model}",
            api_base=settings.gms_base_url,
            api_key=settings.GMS_API_KEY,
            num_retries=1,                               # 마지막 수단이라 1회 재시도
            cache=False,
        )
    return _gms_lm


# 이 에러들만 GMS로 대체 (연결 실패·시간 초과·서버 오류)
# 인증·요청 오류는 그대로 올림 → llm.py와 같은 규칙
_FALLBACK_ERRORS = (LMTransportError, LMTimeoutError, LMServerError)


_slots = threading.BoundedSemaphore(max(1, settings.llm_max_concurrency))


def run_with_fallback(module: dspy.Module, *, _model: str | None = None, **inputs):
    """DSPy 모듈을 로컬 LM으로 실행하고, 연결 실패나 서버 오류면 GMS LM으로 다시 실행한다.
    _model: 로컬에서 쓸 다른 모델 이름 (승격된 LoRA 어댑터). 그 모델이 실패하면 기본 모델로 한 번 더 → 그다음 GMS
    동시에 settings.llm_max_concurrency개까지만 (넘으면 자리가 날 때까지 기다림)"""
    with _slots:
        return _run(module, _model, **inputs)


def _run(module: dspy.Module, model: str | None = None, **inputs):
    if _local_enabled():
        if model and model != settings.local_llm_model:
            # 어댑터: vLLM이 어댑터 없이 다시 떴거나(모델 없음 404) 어댑터 답이 형식에 안 맞으면 기본 모델로
            try:
                with dspy.context(lm=get_local_lm(model)):
                    result = module(**inputs)
                LLM_CALLS.labels("local", "ok").inc()
                return result
            except Exception as e:
                LLM_CALLS.labels("local", "adapter_fallback").inc()
                logger.warning("로컬 어댑터 %s 실패, 기본 모델로 다시: %s", model, str(e)[:200])
        try:
            # dspy.context: 이 블록 안에서만 해당 LM 사용 (요청마다 독립, 스레드 안전)
            with dspy.context(lm=get_local_lm()):
                result = module(**inputs)
            LLM_CALLS.labels("local", "ok").inc()
            return result
        except _FALLBACK_ERRORS as e:
            LLM_CALLS.labels("local", "fallback").inc()      # 이 숫자가 늘면 GPU 서버 vLLM 장애 신호
            logger.warning("로컬 LLM(DSPy) 실패, GMS로 대체: %s", e)

    if _gms_enabled():
        try:
            with dspy.context(lm=get_gms_lm()):
                result = module(**inputs)
        except Exception:
            LLM_CALLS.labels("gms", "error").inc()
            raise
        LLM_CALLS.labels("gms", "ok").inc()
        return result

    raise RuntimeError("사용 가능한 LLM이 없습니다 (로컬 실패 또는 미설정, GMS 미설정)")