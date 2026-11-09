"""OCR 서빙 (PyTriton): 이미지 바이트 → 줄 단위 텍스트 JSON."""
import json
import os

import cv2
import numpy as np
from paddleocr import PaddleOCR
from pytriton.decorators import batch
from pytriton.model_config import ModelConfig, Tensor
from pytriton.triton import Triton, TritonConfig

# 포트 (vLLM 18080과 겹치지 않게)
HTTP_PORT = int(os.getenv("OCR_HTTP_PORT", "18100"))
GRPC_PORT = int(os.getenv("OCR_GRPC_PORT", "18101"))
METRICS_PORT = int(os.getenv("OCR_METRICS_PORT", "18102"))


def _flag(name: str, default: bool) -> bool:
    """환경변수 켜기/끄기 (1/0, true/false). OCR 설정 비교(A/B)를 코드 수정 없이 하려고"""
    return os.getenv(name, str(default)).strip().lower() in ("1", "true", "yes", "on")


def _unwarp_mode() -> str:
    """기울기 보정 모드: on / off / auto(사진만). 예전 값 1·0·true·false도 그대로 받음"""
    v = os.getenv("OCR_DOC_UNWARPING", "auto").strip().lower()
    if v == "auto":
        return "auto"
    return "on" if v in ("1", "true", "yes", "on") else "off"


# OCR 설정 (환경변수로 바꿔 A/B 비교 가능, 기본값은 게이트를 통과한 설정)
OCR_CONFIG = {
    "textline_orientation": _flag("OCR_TEXTLINE_ORIENTATION", False),  # 끔: 멀쩡한 줄을 뒤집어 store 82→88%, 2026-11-05 A/B 결과
    "doc_unwarping": _unwarp_mode(),                                    # 기본 auto: 사진만 보정, test 85→90% (2026-11-06 게이트 통과)
}

# 서버 시작 시 모델을 한 번만 불러옴
ocr = PaddleOCR(
    text_detection_model_name="PP-OCRv5_server_det",          # 글자 영역 찾기
    text_recognition_model_name="korean_PP-OCRv5_mobile_rec",  # 한국어 글자 읽기
    use_doc_orientation_classify=False,
    use_doc_unwarping=OCR_CONFIG["doc_unwarping"] != "off",    # auto도 보정 모델은 불러둠 (요청마다 켜고 끔)
    use_textline_orientation=OCR_CONFIG["textline_orientation"],
    device="gpu:0",                                            # 실제 GPU는 CUDA_VISIBLE_DEVICES로 지정
)
print(f"OCR 설정: {OCR_CONFIG}", flush=True)

SCAN_WHITE_RATIO = 0.8   # 가장자리 픽셀 중 밝은(>200) 비율이 이 이상이면 스캔


def looks_like_scan(img) -> bool:
    """가장자리(짧은 변의 3%)가 거의 다 흰색이면 스캔 → 기울기 보정 불필요 (보정이 오히려 윗줄을 망가뜨림).
    사진은 책상 등 배경이 보여서 어두움. 합성 receipts_v2 300장: 스캔 1.00 / 사진 0.00으로 전부 구분"""
    g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    h, w = g.shape
    b = max(4, int(min(h, w) * 0.03))
    border = np.concatenate([g[:b].ravel(), g[-b:].ravel(), g[:, :b].ravel(), g[:, -b:].ravel()])
    return float((border > 200).mean()) >= SCAN_WHITE_RATIO


def group_lines(texts, scores, boxes):
    """글자 위치(y 중심)가 비슷한 것끼리 한 줄로 묶고, 줄 안에서는 왼쪽→오른쪽 순서로 정렬."""
    items = []
    for t, s, b in zip(texts, scores, boxes):
        x1, y1, x2, y2 = (float(v) for v in b)
        items.append({"text": t, "score": float(s), "box": [x1, y1, x2, y2],
                      "yc": (y1 + y2) / 2, "h": y2 - y1})
    items.sort(key=lambda it: it["yc"])

    lines = []
    for it in items:
        last = lines[-1] if lines else None
        # 줄 높이의 절반 이내로 가까우면 같은 줄로 판단
        if last and abs(it["yc"] - last["yc"]) < 0.5 * max(it["h"], last["h"]):
            last["items"].append(it)
            n = len(last["items"])
            last["yc"] = (last["yc"] * (n - 1) + it["yc"]) / n   # 줄 중심을 평균으로 갱신
            last["h"] = max(last["h"], it["h"])
        else:
            lines.append({"yc": it["yc"], "h": it["h"], "items": [it]})

    text_lines = []
    for ln in lines:
        ln["items"].sort(key=lambda it: it["box"][0])          # 줄 안에서 x 좌표 순
        text_lines.append("  ".join(it["text"] for it in ln["items"]))
    raw = [{k: it[k] for k in ("text", "score", "box")} for it in items]
    return text_lines, raw


def run_ocr(image_bytes: bytes) -> dict:
    """이미지 바이트 하나를 OCR해서 결과 딕셔너리로 반환."""
    img = cv2.imdecode(np.frombuffer(image_bytes, dtype=np.uint8), cv2.IMREAD_COLOR)
    if img is None:
        return {"ok": False, "error": "이미지를 읽을 수 없습니다"}
    # 보정 여부: on이면 항상, auto면 사진일 때만, off면 안 함
    mode = OCR_CONFIG["doc_unwarping"]
    unwarp = mode == "on" or (mode == "auto" and not looks_like_scan(img))
    res = next(iter(ocr.predict(img, use_doc_unwarping=unwarp)))
    lines, items = group_lines(res["rec_texts"], res["rec_scores"], res["rec_boxes"])
    # config: 어떤 OCR 설정으로 읽었는지 같이 돌려줌 → 텍스트셋에 기록 (데이터 계보)
    return {"ok": True, "text": "\n".join(lines), "lines": lines, "items": items,
            "config": OCR_CONFIG, "unwarped": unwarp}


@batch
def infer(image: np.ndarray):
    """Triton 추론 함수. 요청 여러 개가 묶여(batch) 들어오면 하나씩 처리."""
    results = []
    for row in image:                                          # row 모양: (1,) → 이미지 하나
        out = run_ocr(bytes(row[0]))
        results.append([json.dumps(out, ensure_ascii=False).encode("utf-8")])
    return {"result": np.array(results, dtype=np.bytes_)}


def main():
    config = TritonConfig(
        http_address="127.0.0.1", http_port=HTTP_PORT,          # 서버 자신에게만 열기 (Triton은 인증이 없음)
        grpc_address="127.0.0.1", grpc_port=GRPC_PORT,
        metrics_port=METRICS_PORT,
    )
    with Triton(config=config) as triton:
        triton.bind(
            model_name="ocr",
            infer_func=infer,
            inputs=[Tensor(name="image", dtype=np.bytes_, shape=(1,))],
            outputs=[Tensor(name="result", dtype=np.bytes_, shape=(1,))],
            config=ModelConfig(max_batch_size=4),             # 동시에 최대 4장 묶어서 처리
        )
        print(f"OCR 서버 시작: http://127.0.0.1:{HTTP_PORT}/v2/models/ocr")
        triton.serve()


if __name__ == "__main__":
    main()