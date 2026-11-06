"""문서 분류 노드: OCR 텍스트로 문서 종류를 판정해 종류별 분기로 보낸다 (DSPy).

분기 = 문서 종류 (영수증 / 세금계산서 / 출장 정산서 / 휴가 신청서 / 기타)
  확신도가 기준 미만이거나 근거가 원문에 없으면 → '기타' (뒤에 사람 확인을 연결하는 용도)
예) 파일 업로드 → OCR → [문서 분류] ─영수증→ 항목 추출(영수증) → …
                                  └기타→ 사람 확인
"""
from ai.modules.classify import CLASSIFY_VERSION, CONFIDENCE, DOC_TYPES, classify_document
from nodes.base import BaseNode, NodeContext, NodeResult, latest_output


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
    ]

    def run(self, ctx: NodeContext) -> NodeResult:
        text = ctx.config.get("text") or (latest_output(ctx.outputs, "text") or {}).get("text")
        if not text:
            raise ValueError("분류할 텍스트가 없습니다 (앞에 OCR 노드가 필요합니다)")

        r = classify_document(text)
        need = ctx.config.get("min_confidence") or "보통"
        # CONFIDENCE는 높음 → 보통 → 낮음 순: 인덱스가 클수록 낮음
        low = CONFIDENCE.index(r.confidence) > CONFIDENCE.index(need)
        doc_type = "기타" if low else r.doc_type
        return NodeResult(output={
            "doc_type": doc_type, "predicted_type": r.doc_type, "confidence": r.confidence,
            "evidence": r.evidence, "model_version": CLASSIFY_VERSION,
        }, branch=doc_type)