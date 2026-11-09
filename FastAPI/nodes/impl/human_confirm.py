"""사람 확인 노드: 자동 확정되지 않은 결과를 사람이 확인·수정하고, 수정 전/후를 저장."""
from langgraph.types import interrupt

from core.database import SessionLocal
from nodes.base import BaseNode, NodeContext, NodeResult, latest_output
from runs.models import RunFeedback


class HumanConfirmNode(BaseNode):
    node_type = "human_confirm"
    name = "사람 확인"
    category = "human"
    description = "AI가 추출한 값을 사람이 확인하고 필요하면 수정"
    config_fields = [
        {"key": "message", "label": "안내 문구", "type": "text", "required": False},
        {"key": "extraction", "label": "확인할 결과 노드 (예: {{n3}})", "type": "ref", "required": False},
    ]
    branches = ["확인", "반려"]
    output_vars = [
        {"key": "fields", "label": "확정 값", "type": "object"},
        {"key": "edited_fields", "label": "수정된 항목", "type": "list"},
        {"key": "comment", "label": "의견", "type": "text"},
    ]

    def run(self, ctx: NodeContext) -> NodeResult:
        # 바인딩된 노드 출력이 있으면 그것, 없으면 가장 최근의 추출·검증 결과
        extraction = ctx.config.get("extraction") or latest_output(ctx.outputs, "fields")
        if not isinstance(extraction, dict) or "fields" not in extraction:
            raise ValueError("확인할 추출 결과가 없습니다 (앞에 추출 노드가 필요합니다)")
        model_fields = extraction["fields"]

        # 여기서 실행이 멈춤. 재개되면 사용자의 응답이 decision으로 돌아옴
        # (재개 시 노드가 처음부터 다시 실행되므로, 이 위에는 DB 저장 같은 부작용을 두지 않음)
        decision = interrupt({
            "type": "human_confirm",
            "message": ctx.config.get("message") or "추출된 값을 확인해 주세요",
            "fields": model_fields,
            "reasons": extraction.get("reasons", []),       # 왜 사람 확인으로 왔는지
            "gms_fields": extraction.get("gms_fields"),     # GMS가 읽은 값 (참고용)
        })

        # 반려: 값을 확정하지 않으므로 피드백은 저장하지 않음
        if not decision.get("approved"):
            return NodeResult(
                output={"fields": model_fields, "edited_fields": [], "comment": decision.get("comment")},
                branch="반려",
            )

        # 확인: AI 값 위에 사람이 고친 값만 덮어씀
        final_fields = {**model_fields, **(decision.get("data") or {})}
        edited = [k for k, v in final_fields.items() if v != model_fields.get(k)]

        # 모델이 추출한 값일 때만 학습 라벨로 저장
        # (규정 검토 결과처럼 이미 확정된 값을 다시 확인하는 경우는 model_version이 없음 → 저장 안 함)
        if extraction.get("model_version"):
            with SessionLocal() as db:
                db.add(RunFeedback(
                    run_id=ctx.run_id, node_id=ctx.node_id,
                    doc_type=extraction.get("doc_type"),
                    model_version=extraction.get("model_version"),
                    model_output=model_fields, final_output=final_fields,
                    edited_fields=edited, label_source="human",
                ))
                db.commit()

        return NodeResult(
            output={"fields": final_fields, "edited_fields": edited, "comment": decision.get("comment")},
            branch="확인",
        )