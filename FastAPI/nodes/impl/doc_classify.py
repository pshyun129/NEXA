"""문서 분류 노드: OCR 텍스트로 문서 종류를 판정해 종류별 분기로 보낸다 (DSPy).

분기 = 문서 종류 (영수증 / 세금계산서 / 출장 정산서 / 휴가 신청서 / 기타)
  확신도가 기준 미만이거나 근거가 원문에 없으면 → '기타' (뒤에 사람 확인을 연결하는 용도)
예) 파일 업로드 → OCR → [문서 분류] ─영수증→ 항목 추출(영수증) → …
                                  └기타→ 사람 확인
"""
from collections import Counter
from concurrent.futures import ThreadPoolExecutor

from ai.modules.classify import CLASSIFY_VERSION, CONFIDENCE, DOC_TYPES, classify_document
from nodes.base import BaseNode, NodeContext, NodeResult, Progress, latest_output

MAX_WORKERS = 8      # 여러 장 분류 동시 처리 (vLLM 동시 한도와 맞춤)


class DocClassifyNode(BaseNode):
    node_type = "doc_classify"
    name = "문서 분류"
    category = "ai"
    description = "OCR 텍스트로 문서 종류(영수증·세금계산서·출장 정산서·휴가 신청서·기타)를 판정해 분기"
    config_fields = [
        {"key": "text", "label": "입력 텍스트 (예: {{n2.text}}, 비우면 가장 최근 텍스트)", "type": "ref", "required": False},
        {"key": "min_confidence", "label": "최소 확신도 (기본 보통 · 이보다 낮으면 '기타')", "type": "select",
         "options": ["높음", "보통"], "required": False},
    ]
    branches = DOC_TYPES
    output_vars = [
        {"key": "doc_type", "label": "문서 종류 (분기 기준)", "type": "text"},
        {"key": "predicted_type", "label": "모델이 고른 종류", "type": "text"},
        {"key": "confidence", "label": "확신도", "type": "text"},
        {"key": "evidence", "label": "판단 근거 문구", "type": "text"},
        {"key": "model_version", "label": "모델 버전", "type": "text"},
        {"key": "page_types", "label": "장별 종류 (여러 장일 때)", "type": "list"},
    ]

    def run(self, ctx: NodeContext) -> NodeResult:
        need = ctx.config.get("min_confidence") or "보통"

        def judge(text: str):
            r = classify_document(text)
            # CONFIDENCE는 높음 → 보통 → 낮음 순: 인덱스가 클수록 낮음
            low = CONFIDENCE.index(r.confidence) > CONFIDENCE.index(need)
            return r, ("기타" if low else r.doc_type)

        # 여러 장: 장마다 분류해서 page_types로 넘김 → 추출 노드가 다른 종류의 장은 제외
        # 분기는 가장 많은 종류 (영수증 묶음에 엉뚱한 서류 한 장이 섞여도 흐름은 영수증으로 감)
        pages = (latest_output(ctx.outputs, "pages") or {}).get("pages") or []
        if len(pages) >= 2:
            with ThreadPoolExecutor(max_workers=MAX_WORKERS) as pool:
                progress = Progress(ctx, len(pages), "문서 분류")

                def one(p):
                    out = judge(p["text"])
                    progress.tick()
                    return out
                results = list(pool.map(one, pages))
            page_types = [{"index": i, "file_id": p.get("file_id"), "name": p.get("name"), "doc_type": t,
                           "confidence": r.confidence, "evidence": r.evidence} for i, (p, (r, t)) in enumerate(zip(pages, results))]
            doc_type = Counter(t for _, t in results).most_common(1)[0][0]
            return NodeResult(output={
                "doc_type": doc_type, "predicted_type": doc_type,
                # 그 종류로 분류된 장들 중 가장 낮은 확신도 (CONFIDENCE는 높음→낮음 순이라 인덱스가 큰 쪽)
                "confidence": max((r.confidence for r, t in results if t == doc_type), key=CONFIDENCE.index, default="낮음"),
                "evidence": f"{len(pages)}장 중 {sum(t == doc_type for _, t in results)}장이 {doc_type}",
                "model_version": CLASSIFY_VERSION, "page_types": page_types,
            }, branch=doc_type)

        text = ctx.config.get("text") or (latest_output(ctx.outputs, "text") or {}).get("text")
        if not text:
            raise ValueError("분류할 텍스트가 없습니다 (앞에 OCR 노드가 필요합니다)")
        r, doc_type = judge(text)
        return NodeResult(output={
            "doc_type": doc_type, "predicted_type": r.doc_type, "confidence": r.confidence,
            "evidence": r.evidence, "model_version": CLASSIFY_VERSION,
        }, branch=doc_type)