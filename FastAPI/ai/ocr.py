"""OCR 서버(PyTriton) 호출 클라이언트: 이미지 바이트 → 줄 단위 텍스트."""
import json

import time

import numpy as np
from pydantic import BaseModel, Field
from tritonclient.http import InferenceServerClient, InferInput, InferRequestedOutput

from core.config import settings
from core.metrics import OCR_ERRORS, OCR_SECONDS


class OcrItem(BaseModel):
    """글자 덩어리 하나 (원본 위치·신뢰도)."""
    text: str
    score: float          # 인식 신뢰도 (0~1)
    box: list[float]      # [x1, y1, x2, y2]


class OcrResult(BaseModel):
    """OCR 결과."""
    text: str             # 줄 단위로 정리한 전체 텍스트 → DSPy 추출 입력
    lines: list[str]      # 줄 목록
    items: list[OcrItem]  # 글자별 원본 (신뢰도 낮은 글자 표시, 학습 데이터용)
    config: dict = Field(default_factory=dict)  # 서버의 OCR 설정 (예전 서버는 안 보내므로 기본값 빈 dict)


def _client() -> InferenceServerClient:
    """요청마다 새 클라이언트 생성.
    Triton HTTP 클라이언트는 여러 스레드가 함께 쓰기에 안전하지 않아서,
    FastAPI가 요청을 여러 스레드로 처리할 때 공유하지 않도록 함."""
    return InferenceServerClient(
        url=settings.ocr_base_url,
        connection_timeout=5.0,                 # 연결 시도 최대 5초 (서버 꺼짐 빠르게 감지)
        network_timeout=settings.ocr_timeout,   # 응답 대기 최대 시간
    )


def ocr_image(image_bytes: bytes) -> OcrResult:
    """이미지 바이트를 OCR 서버로 보내고 결과를 돌려준다."""
    # 입력 이름·모양·타입은 서버(ocr_server.py)의 설정과 같아야 함
    inp = InferInput("image", [1, 1], "BYTES")
    inp.set_data_from_numpy(np.array([[image_bytes]], dtype=np.object_))

    start = time.perf_counter()
    client = _client()
    try:
        res = client.infer("ocr", [inp], outputs=[InferRequestedOutput("result")])
    except Exception:
        OCR_ERRORS.inc()               # 연결 실패·시간 초과 등
        raise
    finally:
        client.close()
        OCR_SECONDS.observe(time.perf_counter() - start)

    data = json.loads(res.as_numpy("result")[0][0])
    if not data.get("ok"):
        OCR_ERRORS.inc()               # 서버는 응답했지만 이미지 처리 실패
        raise ValueError(f"OCR 실패: {data.get('error')}")
    return OcrResult(**data)


def ocr_status() -> str:
    """OCR 서버 상태: up / down / disabled."""
    if not settings.ocr_base_url:
        return "disabled"
    client = _client()
    try:
        return "up" if client.is_model_ready("ocr") else "down"
    except Exception:
        return "down"
    finally:
        client.close()