"""결재 상신 노드: 확정된 값으로 결재 문서를 만들어 상신.

결재선은 결재 서비스가 위임전결 규정(양식·금액·기안자 소속)으로 자동 결정한다.
"""
import string
import uuid

from clients.approval import create_document
from core.config import settings
from engine.tokens import get_token
from nodes.base import BaseNode, NodeContext, NodeResult, latest_output

FORM_TYPES = {"일반 기안": "GENERAL"}   # 화면에 보일 이름 → 결재 서비스 FormType
_KEY_NAMESPACE = uuid.uuid5(uuid.NAMESPACE_URL, "nexa/ai-service/approval-draft")


def make_request_key(run_id: int, node_id: str) -> uuid.UUID:
    """실행·노드마다 항상 같은 키 → 같은 노드가 다시 실행돼도 문서가 두 번 안 생김"""
    return uuid.uuid5(_KEY_NAMESPACE, f"{run_id}:{node_id}")


def _render_title(template: str, values: dict) -> str:
    """'[경비] {store} {total:,}원' → '[경비] 카페 온도 역삼점 17,900원'"""
    names = {name for _, name, _, _ in string.Formatter().parse(template) if name}
    missing = names - values.keys()
    if missing:
        raise ValueError(
            f"제목에 쓴 항목을 앞 노드 결과에서 찾을 수 없습니다: {', '.join(sorted(missing))} "
            f"(사용 가능: {', '.join(sorted(values))})"
        )
    return template.format_map(values)


class ApprovalDraftNode(BaseNode):
    node_type = "approval_draft"
    name = "결재 상신"
    category = "output"
    description = "확정된 값으로 결재 문서를 상신 (결재선은 위임전결 규정으로 자동 결정)"
    config_fields = [
        {"key": "form_type", "label": "문서 양식", "type": "select", "options": list(FORM_TYPES), "required": True},
        {"key": "title", "label": "제목 (예: [경비] {store} {total:,}원)", "type": "text", "required": True},
        {"key": "fields", "label": "확정 값 (예: {{n4.fields || n3.fields}})", "type": "ref", "required": False},
        {"key": "employee", "label": "기안자 정보 (예: {{n5.employee}})", "type": "ref", "required": False},
        {"key": "amount_field", "label": "금액 항목 (기본 total)", "type": "text", "required": False},
    ]
    branches = []
    output_vars = [
        {"key": "document_id", "label": "문서 ID", "type": "number"},
        {"key": "doc_no", "label": "문서 번호", "type": "text"},
        {"key": "request_key", "label": "요청 키", "type": "text"},
        {"key": "title", "label": "제목", "type": "text"},
        {"key": "amount", "label": "금액", "type": "number"},
    ]

    def run(self, ctx: NodeContext) -> NodeResult:
        token = get_token(ctx.run_id)
        if not token:
            raise ValueError("로그인 토큰이 없어 결재를 상신할 수 없습니다 (실행·재개 요청에 토큰 필요)")

        # 바인딩된 값이 있으면 그것, 없으면 가장 최근 확정 값·사원 정보
        fields = ctx.config.get("fields") or (latest_output(ctx.outputs, "fields") or {}).get("fields") or {}
        employee = ctx.config.get("employee") or (latest_output(ctx.outputs, "employee") or {}).get("employee") or {}
        values = {**fields, "requester": employee.get("name", "")}
        title = _render_title(ctx.config["title"], values)

        amount = fields.get(ctx.config.get("amount_field") or "total")
        form_type = FORM_TYPES[ctx.config["form_type"]]
        key = make_request_key(ctx.run_id, ctx.node_id)

        doc = create_document(
            title=title,
            form_type=form_type,
            token=token,
            source_type=settings.approval_source_type,
            source_id=ctx.run_id,                        # DIRECT면 클라이언트가 안 보냄
            request_key=key,
            attributes={"amount": int(amount)} if amount is not None else None,
        )
        return NodeResult(output={
            "document_id": doc["documentId"],
            "doc_no": doc["docNo"],
            "request_key": str(key),   # 결재 결과 이벤트와 이 실행을 연결하는 열쇠
            "title": title,
            "amount": amount,
            "form_type": form_type,
        })