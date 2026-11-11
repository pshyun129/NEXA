"""결재 대기 노드: 상신한 결재 문서의 결과(승인/반려/회수)를 기다렸다가 분기.

결과는 Kafka 이벤트로 오며, 먼저 결과 보관함을 보고 이미 와 있으면 기다리지 않고 바로 지나간다.
이벤트로 재개되므로 이 노드 뒤에는 사용자 토큰이 없다 → 팀원 API를 부르는 노드를 두지 않는다.

문서가 여러 건(영수증 여러 장)이면 모두 결과가 날 때까지 기다린다. 결과가 하나 올 때마다 재개되어 다시 확인하고,
아직 남은 문서가 있으면 다시 멈춘다 (interrupt를 여러 번 부름 → LangGraph가 순서대로 재개 값을 맞춰 줌).
분기: 한 건이라도 승인 → 승인(승인된 장만 정산), 승인 없이 반려가 있으면 → 반려, 모두 회수 → 회수
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
        {"key": "documents", "label": "문서별 결과 (여러 건)", "type": "list"},
        {"key": "receipts", "label": "장별 결과 (승인 여부 포함)", "type": "list"},
    ]

    def run(self, ctx: NodeContext) -> NodeResult:
        # 바인딩된 상신 노드 출력이 있으면 그것, 없으면 가장 최근 상신 결과
        draft = ctx.config.get("draft") or latest_output(ctx.outputs, "request_key")
        if not isinstance(draft, dict) or not draft.get("request_key"):
            raise ValueError("기다릴 결재 문서가 없습니다 (앞에 결재 상신 노드가 필요합니다)")
        if len(draft.get("documents") or []) > 1:
            return self._many(draft)
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

    def _many(self, draft: dict) -> NodeResult:
        docs = draft["documents"]
        while True:
            results = {d["request_key"]: get_result(d["request_key"]) for d in docs}
            missing = [d for d in docs if results[d["request_key"]] is None]
            if not missing:
                break
            # 남은 문서가 있으면 멈춤. 어느 문서의 결과든 오면 재개되어 처음부터 다시 확인
            interrupt({
                "type": "approval_wait",
                "message": f"결재 결과를 기다리는 중입니다 ({len(docs) - len(missing)}/{len(docs)}건 완료)",
                "request_key": missing[0]["request_key"],
                "request_keys": [d["request_key"] for d in missing],
                "document_id": missing[0].get("document_id"), "doc_no": missing[0].get("doc_no"),
                "documents": [{k: d.get(k) for k in ("document_id", "doc_no", "title", "kind")}
                              | {"status": (results[d["request_key"]] or {}).get("status")} for d in docs],
            })

        out_docs = [{**d, "status": results[d["request_key"]]["status"],
                     "doc_no": d.get("doc_no") or results[d["request_key"]].get("doc_no")} for d in docs]
        status_by_key = {d["request_key"]: d["status"] for d in out_docs}
        receipts = []
        for r in draft.get("receipts") or []:
            key = (r.get("approval") or {}).get("request_key")
            st = status_by_key.get(key)
            receipts.append({**r, "approval": {**(r.get("approval") or {}), "status": st},
                             "settled": st == "APPROVED"})          # 정산 포함 = 그 문서가 승인됨
        statuses = [d["status"] for d in out_docs]
        if all(s == "APPROVED" for s in statuses):
            overall = "APPROVED"
        elif "APPROVED" in statuses:
            overall = "PARTIAL"
        elif "REJECTED" in statuses:
            overall = "REJECTED"
        else:
            overall = "CANCEL"
        branch = BRANCHES["APPROVED"] if "APPROVED" in statuses else BRANCHES["REJECTED"] if "REJECTED" in statuses else BRANCHES["CANCEL"]
        return NodeResult(
            output={
                "approval_status": overall,
                "document_id": out_docs[0].get("document_id"), "doc_no": out_docs[0].get("doc_no"),
                "documents": out_docs, "receipts": receipts, "count": len(receipts),
                "approved_count": statuses.count("APPROVED"), "rejected_count": statuses.count("REJECTED"),
                "canceled_count": statuses.count("CANCEL"),
            },
            branch=branch,
        )