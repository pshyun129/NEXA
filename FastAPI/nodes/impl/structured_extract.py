"""항목 추출 노드 : OCR 텍스트 -> 문서 종류별 구조화 데이터 (DSPy)."""
from datetime import date

from ai.modules.receipt import RECEIPT_VERSION, extract_receipt
from nodes.base import BaseNode, NodeContext, NodeResult, latest_output

# 문서 종류 -> (추출 함수, 모델 버전)
# 새 문서 종류는 DSPy 모듈을 만들고 여기에 한 줄 추가
# 모델 버전 = 서버가 불러온 챔피언 버전 (ai/programs/*.meta.json, 없으면 base)
EXTRACTORS = {
    "영수증": (extract_receipt, RECEIPT_VERSION),
}


def _validate(doc_type: str, fields: dict) -> list[str]:
    """문서 종류별 필수값·형식 검사. 문제가 있는 필드 이름 목록을 돌려준다."""
    problems = []
    if doc_type == "영수증":
        if not fields.get("store"):
            problems.append("store")
        if not isinstance(fields.get("total"), int) or fields["total"] <= 0:
            problems.append("total")
        try:
            date.fromisoformat(fields.get("date") or "")  # YYYY-MM-DD 형식인지
        except ValueError:
            problems.append("date")
    return problems


class StructuredExtractNode(BaseNode):
    node_type = "structured_extract"
    name = "서류 항목 추출"
    category = "ai"
    description = "OCR 텍스트에서 문서 종류에 맞는 항목을 추출 (DSPy + LLM)"
    config_fields = [
        {"key": "doc_type", "label": "문서 종류", "type": "select",
         "options": list(EXTRACTORS.keys()), "required": True},
        {"key": "text", "label": "입력 텍스트 (예: {{n1.text}}, 비우면 가장 최근 텍스트)", "type": "ref",
         "required": False},
    ]
    branches = ["성공", "확인 필요"]
    output_vars = [
        {"key": "fields", "label": "추출 결과", "type": "object"},
        {"key": "doc_type", "label": "문서 종류", "type": "text"},
        {"key": "problems", "label": "확인이 필요한 항목", "type": "list"},
        {"key": "model_version", "label": "모델 버전", "type": "text"},
    ]

    def run(self, ctx: NodeContext) -> NodeResult:
        doc_type = ctx.config["doc_type"]
        if doc_type not in EXTRACTORS:
            raise ValueError(f"지원하지 않는 문서 종류입니다: {doc_type}")

        # 바인딩된 텍스트가 있으면 그것, 없으면 앞 노드 중 가장 최근 text
        text = ctx.config.get("text") or (latest_output(ctx.outputs, "text") or {}).get("text")
        if not text:
            raise ValueError("추출할 텍스트가 없습니다 (앞에 OCR 노드가 필요합니다)")

        extract, model_version = EXTRACTORS[doc_type]
        fields = extract(text).model_dump()  # pydantic 객체 → dict (JSON 저장 가능하게)

        problems = _validate(doc_type, fields)
        branch = "확인 필요" if problems else "성공"
        return NodeResult(
            output={"fields": fields, "doc_type": doc_type,
                    "problems": problems, "model_version": model_version},
            branch=branch,
        )