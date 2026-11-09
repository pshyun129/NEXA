"""결재 대기 노드: 상신한 결재 문서의 결과(승인/반려/회수)를 기다렸다가 분기.

결과는 Kafka 이벤트로 오며, 먼저 결과 보관함을 보고 이미 와 있으면 기다리지 않고 바로 지나간다.
이벤트로 재개되므로 이 노드 뒤에는 사용자 토큰이 없다 → 팀원 API를 부르는 노드를 두지 않는다.
"""
from langgraph.types import interrupt

from events.approval_results import get_result
from nodes.base import BaseNode, NodeContext, NodeResult, latest_output

BRANCHES = {"APPROVED": "승인", "REJECTED": "반려", "CANCEL": "회수"}


class WaitApprovalNode(BaseNode):
    node_type = "wait_approval"
    name = "결재 대기"
    category = "human"
    description = "상신한 결재 문서가 승인·반려·회수될 때까지 기다림 (반려·회수는 최종)"
    config_fields = [
        {"key": "draft", "label": "결재 상신 노드 (예: {{n6}})", "type": "ref", "required": False},
    ]
    branches = list(BRANCHES.values())
    output_vars = [
        {"key": "approval_status", "label": "결재 결과", "type": "text"},
        {"key": "document_id", "label": "문서 ID", "type": "number"},
        {"key": "doc_no", "label": "문서 번호", "type": "text"},
    ]

    def run(self, ctx: NodeContext) -> NodeResult:
        # 바인딩된 상신 노드 출력이 있으면 그것, 없으면 가장 최근 상신 결과
        draft = ctx.config.get("draft") or latest_output(ctx.outputs, "request_key")
        if not isinstance(draft, dict) or not draft.get("request_key"):
            raise ValueError("기다릴 결재 문서가 없습니다 (앞에 결재 상신 노드가 필요합니다)")
        request_key = draft["request_key"]

        # 이미 도착한 결과가 있으면 기다리지 않음
        # (결재선이 본인뿐이라 즉시 승인된 경우, 재개되어 이 노드가 다시 실행되는 경우 모두)
        result = get_result(request_key)
        if result is None:
            # 여기서 멈춤. Kafka 이벤트가 오면 보관함에 저장된 결과로 재개됨
            result = interrupt({
                "type": "approval_wait",
                "message": "결재 결과를 기다리는 중입니다",
                "request_key": request_key,
                "document_id": draft.get("document_id"),
                "doc_no": draft.get("doc_no"),
            })

        status = result["status"]
        return NodeResult(
            output={
                "approval_status": status,
                "document_id": draft.get("document_id") or result.get("document_id"),
                "doc_no": draft.get("doc_no") or result.get("doc_no"),
            },
            branch=BRANCHES[status],
        )