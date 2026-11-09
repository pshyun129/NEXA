"""업무 완료 노드: 실행 결과에 사람이 읽을 요약 메시지를 남기고 끝낸다 (LLM 없음).

예) 메시지 "{{n3.fields.store}} {{n3.fields.total}}원 경비 상신 완료 (문서 {{n7.doc_no}})"
    → 실행 목록·상세 화면에서 이 메시지를 결과로 보여줌 (마지막 노드로 두는 용도)
분기 끝마다 하나씩 두면 "어떤 길로 끝났는지"가 결과에 남음 (예: 상신 완료 / 반려 처리)
"""
from nodes.base import BaseNode, NodeContext, NodeResult

RESULTS = ["완료", "반려", "보류"]


class CompleteNode(BaseNode):
    node_type = "complete"
    name = "업무 완료"
    category = "output"
    description = "실행 결과에 요약 메시지를 남기고 끝냄 (분기 끝마다 두면 어떤 결과로 끝났는지 기록)"
    config_fields = [
        {"key": "result", "label": "결과", "type": "select", "options": RESULTS, "required": True},
        {"key": "message", "label": "요약 메시지 (예: {{n3.fields.store}} {{n3.fields.total}}원 상신 완료)",
         "type": "text", "required": False},
    ]
    branches = []
    output_vars = [
        {"key": "result", "label": "결과", "type": "text"},
        {"key": "message", "label": "요약 메시지", "type": "text"},
    ]

    def run(self, ctx: NodeContext) -> NodeResult:
        result = ctx.config["result"]
        message = str(ctx.config.get("message") or "").strip() or f"업무 {result}"
        return NodeResult(output={"result": result, "message": message, "is_final": True})