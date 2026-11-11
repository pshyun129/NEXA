"""OCR 노드: 업로드된 이미지 파일 → 줄 단위 텍스트."""
from pathlib import Path

from ai.ocr import ocr_image
from concurrent.futures import ThreadPoolExecutor

from nodes.base import BaseNode, NodeContext, NodeResult, Progress

OCR_WORKERS = 4         # GPU 서버 OCR 동시 호출 수 (Triton이 묶어서 처리)

# OCR 대상 확장자 (PDF는 나중에 페이지를 이미지로 바꿔서 지원)
IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}


class OcrExtractNode(BaseNode):
    node_type = "ocr_extract"
    name = "OCR 글자 인식"
    category = "ai"
    description = "업로드한 이미지에서 글자를 읽어 줄 단위 텍스트로 변환"
    config_fields = [
        {"key": "min_confidence", "label": "최소 신뢰도", "type": "number", "required": False},
        {"key": "files", "label": "OCR할 파일 (예: {{n0.files}}, 비우면 첨부 파일 전체)", "type": "ref", "required": False},
    ]
    branches = ["성공", "확인 필요"]
    output_vars = [
        {"key": "text", "label": "인식 텍스트", "type": "text"},
        {"key": "pages", "label": "파일별 텍스트", "type": "list"},
        {"key": "confidence", "label": "평균 신뢰도", "type": "number"},
    ]

    def run(self, ctx: NodeContext) -> NodeResult:
        # 바인딩된 파일 목록(파일 업로드 노드 등)이 있으면 그것, 없다면 실행 시 첨부된 파일 전체
        source = ctx.config.get("files") or ctx.inputs.get("files", [])
        files = [f for f in source if Path(f["path"]).suffix.lower() in IMAGE_EXTS]
        if not files:
            raise ValueError("OCR할 이미지 파일이 없습니다")

        progress = Progress(ctx, len(files), "글자 인식")

        def read(f: dict):
            result = ocr_image(Path(f["path"]).read_bytes())          # GPU 서버 OCR 호출
            progress.tick()
            return result

        with ThreadPoolExecutor(max_workers=OCR_WORKERS) as pool:     # 순서는 첨부 순서 그대로
            results = list(pool.map(read, files))
        pages, scores = [], []
        for f, result in zip(files, results):
            page_scores = [item.score for item in result.items]       # 글자 덩어리별 신뢰도
            pages.append({"file_id": f["file_id"], "name": f["name"], "text": result.text,
                          # 장별 신뢰도: 여러 장일 때 교차 검증이 장마다 기준을 적용 (흐린 한 장만 사람 확인)
                          "confidence": round(sum(page_scores) / len(page_scores), 4) if page_scores else 0.0})
            scores += page_scores

        # 평균 신뢰도가 기준 미만이면 "확인 필요" 분기 (흐리거나 잘린 사진)
        confidence = round(sum(scores) / len(scores), 4) if scores else 0.0
        threshold = float(ctx.config.get("min_confidence") or 0.8)
        branch = "성공" if confidence >= threshold else "확인 필요"

        # 여러 장이면 빈 줄로 구분해서 합침
        text = "\n\n".join(p["text"] for p in pages)
        return NodeResult(
            output={"text": text, "pages": pages, "confidence": confidence},
            branch=branch,
        )