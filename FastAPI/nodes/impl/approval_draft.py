"""결재 상신 노드: 확정된 값으로 결재 문서를 만들어 상신.

결재선은 결재 서비스가 위임전결 규정(양식·금액·기안자 소속)으로 자동 결정한다.

영수증 여러 장(receipts, 정산에서 뺀 장 제외)이면 문서를 나눠 만든다:
  - 규정에 걸린 장(위반·확인 필요, 사람 확인 뒤에도 남은 것): 장마다 한 건 → 결재자가 사유를 보고 장별로 판단
  - 준수한 장: 계정과목·월마다 한 건으로 묶음 (제목에 건수·합계, 금액은 합계)
결재 문서에는 본문이 없어서 장별 내역은 [원본 실행 보기](결재자도 읽기 전용으로 볼 수 있음)와 정산서 엑셀로 본다.
"""
import string
import uuid
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor

from clients.approval import create_document
from core.config import settings
from ai.receipts.accounts import account_of, purpose_of, verdict_of
from engine.tokens import get_token
from nodes.base import BaseNode, NodeContext, NodeResult, Progress, latest_output

FORM_TYPES = {"일반 기안": "GENERAL"}   # 화면에 보일 이름 → 결재 서비스 FormType
_KEY_NAMESPACE = uuid.uuid5(uuid.NAMESPACE_URL, "nexa/ai-service/approval-draft")
GROUPINGS = ["목적·월별 묶음 (걸린 장은 건별)", "장마다 한 건"]
TITLE_MAX = 255
DOC_WORKERS = 4


def make_request_key(run_id: int, node_id: str, group: str | None = None) -> uuid.UUID:
    """실행·노드(·문서 묶음)마다 항상 같은 키 → 같은 노드가 다시 실행돼도 문서가 두 번 안 생김"""
    return uuid.uuid5(_KEY_NAMESPACE, f"{run_id}:{node_id}" + (f":{group}" if group else ""))


def _short_clause(label: str) -> str:
    """'경비 및 법인카드 사용 규정 제8조(식대)' → '제8조(식대)'"""
    return label.split("규정 ")[-1] if "규정 " in label else label


def _issue(r: dict) -> str:
    findings = (r.get("policy") or {}).get("findings") or []
    bad = [f for f in findings if f.get("verdict") in ("위반", "확인 필요")]
    if not bad:
        return verdict_of(r)
    head = f"{_short_clause(bad[0].get('label', ''))} {bad[0]['verdict']}"
    return head + (f" 외 {len(bad) - 1}건" if len(bad) > 1 else "")


def plan_documents(receipts: list[dict], grouping: str, requester: str) -> list[dict]:
    """장 목록 → 만들 문서 목록 [{group, kind, title, amount, receipts(index)}]"""
    docs: list[dict] = []
    bundles: "OrderedDict[tuple, list[dict]]" = OrderedDict()
    who = f" ({requester})" if requester else ""
    for r in receipts:
        f = r["fields"]
        if grouping == GROUPINGS[1] or verdict_of(r) != "준수":
            kind = "단건" if grouping == GROUPINGS[1] and verdict_of(r) == "준수" else "확인"
            title = (f"[경비·{kind}] {f.get('date') or ''} {f.get('store') or ''} {int(f.get('total') or 0):,}원"
                     f" ({purpose_of(r) or '목적 미기재'})" + ("" if kind == "단건" else f" · {_issue(r)}") + who)
            docs.append({"group": f"r{r.get('index', 0)}", "kind": "flagged" if kind == "확인" else "single",
                         "title": title, "amount": int(f.get("total") or 0), "receipts": [r.get("index", 0)],
                         "account": account_of(r), "month": str(f.get("date") or "")[:7]})
        else:
            bundles.setdefault((account_of(r), str(f.get("date") or "")[:7] or "날짜 없음"), []).append(r)
    for (account, month), rs in bundles.items():
        total = sum(int(x["fields"].get("total") or 0) for x in rs)
        stores = list(OrderedDict.fromkeys(x["fields"].get("store") or "" for x in rs))
        where = stores[0] + (f" 외 {len(stores) - 1}곳" if len(stores) > 1 else "")
        docs.append({"group": f"{account}:{month}", "kind": "bundle", "account": account, "month": month,
                     "title": f"[경비] {month} {account} {len(rs)}건 {total:,}원 ({where}){who}",
                     "amount": total, "receipts": [x.get("index", 0) for x in rs]})
    for d in docs:
        d["title"] = d["title"][:TITLE_MAX]
    return docs


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
        {"key": "grouping", "label": "여러 장일 때 문서 나누기", "type": "select", "options": GROUPINGS, "required": False},
        {"key": "receipts", "label": "여러 장 결과 노드 (예: {{n9}}, 비우면 가장 최근 장별 결과)", "type": "ref", "required": False},
    ]
    branches = []
    output_vars = [
        {"key": "document_id", "label": "문서 ID", "type": "number"},
        {"key": "doc_no", "label": "문서 번호", "type": "text"},
        {"key": "request_key", "label": "요청 키", "type": "text"},
        {"key": "title", "label": "제목", "type": "text"},
        {"key": "amount", "label": "금액", "type": "number"},
        {"key": "documents", "label": "상신한 문서 목록 (여러 장)", "type": "list"},
        {"key": "request_keys", "label": "요청 키 목록", "type": "list"},
    ]

    def run(self, ctx: NodeContext) -> NodeResult:
        token = get_token(ctx.run_id)
        if not token:
            raise ValueError("로그인 토큰이 없어 결재를 상신할 수 없습니다 (실행·재개 요청에 토큰 필요)")

        # 바인딩된 값이 있으면 그것, 없으면 가장 최근 확정 값·사원 정보
        fields = ctx.config.get("fields") or (latest_output(ctx.outputs, "fields") or {}).get("fields") or {}
        employee = ctx.config.get("employee") or (latest_output(ctx.outputs, "employee") or {}).get("employee") or {}
        source = ctx.config.get("receipts") or latest_output(ctx.outputs, "receipts") or {}
        all_receipts = (source.get("receipts") or []) if isinstance(source, dict) else []
        receipts = [r for r in all_receipts if r.get("include", True)]
        if len(receipts) > 1:
            return self._many(ctx, token, receipts, employee, all_receipts)
        if receipts:                                       # 한 장만 남았으면 예전처럼 제목 틀로
            fields = receipts[0]["fields"]
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
            "documents": [{"group": "single", "kind": "single", "document_id": doc["documentId"], "doc_no": doc["docNo"],
                           "request_key": str(key), "title": title, "amount": amount,
                           "receipts": [receipts[0].get("index", 0)] if receipts else []}],
            "request_keys": [str(key)],
            **({"receipts": receipts, "count": 1} if receipts else {}),
        })

    def _many(self, ctx: NodeContext, token: str, receipts: list[dict], employee: dict, all_receipts: list[dict]) -> NodeResult:
        form_type = FORM_TYPES[ctx.config["form_type"]]
        plans = plan_documents(receipts, ctx.config.get("grouping") or GROUPINGS[0], employee.get("name", ""))
        progress = Progress(ctx, len(plans), "결재 상신")

        def create(p: dict) -> dict:
            key = make_request_key(ctx.run_id, ctx.node_id, p["group"])
            doc = create_document(title=p["title"], form_type=form_type, token=token,
                                  source_type=settings.approval_source_type, source_id=ctx.run_id,
                                  request_key=key, attributes={"amount": p["amount"]})
            progress.tick()
            return {**p, "document_id": doc["documentId"], "doc_no": doc["docNo"], "request_key": str(key)}

        with ThreadPoolExecutor(max_workers=DOC_WORKERS) as pool:
            docs = list(pool.map(create, plans))
        by_index = {i: d for d in docs for i in d["receipts"]}
        # 정산에서 뺀 장도 목록에 남김 (정산서에 'X 정산에서 뺌'으로 보이게)
        out_receipts = [{**r, "approval": {k: by_index[r.get("index", 0)][k] for k in ("request_key", "doc_no", "document_id", "title", "kind")}}
                        if r.get("index", 0) in by_index else r for r in all_receipts]
        first = docs[0]
        return NodeResult(output={
            "document_id": first["document_id"], "doc_no": first["doc_no"], "request_key": first["request_key"],
            "title": first["title"], "amount": sum(d["amount"] for d in docs), "form_type": form_type,
            "documents": docs, "request_keys": [d["request_key"] for d in docs],
            "receipts": out_receipts, "count": len(out_receipts),
        })