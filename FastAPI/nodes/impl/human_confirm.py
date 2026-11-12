"""사람 확인 노드: 자동 확정되지 않은 결과를 사람이 확인·수정하고, 수정 전/후를 저장.

영수증 여러 장(receipts)이면 장마다:
  - 추출 값(상호·금액·날짜) 수정, 규정 검토에 쓸 사실(사용 목적·참석 인원·참석자·사용 사유) 입력
  - 정산에서 빼기(include=False), 결재자에게 남길 설명(note)
  - 규정 검토 뒤의 확인이면, 고친 장은 확인 직후 다시 검토 → 그래도 걸린 장은 결재 상신에서 건별 문서가 됨
화면은 걸린 장(flagged)을 먼저 보여 주고, 나머지는 펼쳐서 볼 수 있다.
"""
from langgraph.types import interrupt

from core.database import SessionLocal
from nodes.base import BaseNode, NodeContext, NodeResult, latest_output
from runs.models import RunFeedback

FACT_KEYS = ("purpose", "headcount", "attendees", "reason", "pay_method", "pay_time")
KNOWN_NAMES = {"purpose": "사용 목적", "headcount": "참석 인원", "attendees": "참석자 이름", "reason": "사용 사유",
               "pay_method": "결제 수단", "pay_time": "결제 시각"}
MAX_ITEMS = 30          # 화면에 보낼 품목 수 (장마다)


def _flag(r: dict) -> tuple[bool, list[str]]:
    """이 장을 사람이 봐야 하는지와 그 이유 (규정 검토 결과가 있으면 그것, 없으면 자동 검증 결과)"""
    policy = r.get("policy")
    if policy:
        return policy.get("verdict") != "준수", policy.get("reasons") or []
    verify = r.get("verify") or {}
    reasons = list(verify.get("reasons") or [])
    return verify.get("route") == "human" or bool(r.get("problems")), reasons


def _facts(r: dict) -> dict:
    """화면에 미리 채울 사실: 사람이 이미 적은 값 → 규정 검토에 쓴 값"""
    known = (r.get("policy") or {}).get("known_facts") or {}
    out = {}
    for key in FACT_KEYS:
        v = (r.get("facts") or {}).get(key)
        if v in (None, "", []):
            v = known.get(KNOWN_NAMES[key])
            # 품목으로 추정한 인원은 사람이 적은 값처럼 칸에 채우지 않음 (화면은 추정 범위를 도움말로 보여 줌)
            if key == "headcount" and str(known.get("참석 인원 근거", "")).startswith("품목 기준"):
                v = None
        if v not in (None, "", []):
            out[key] = ", ".join(v) if isinstance(v, list) else v
    return out


def compact(r: dict) -> dict:
    """대기 화면용 장 정보 (원문·조항 인용 같은 큰 값은 뺌)"""
    flagged, reasons = _flag(r)
    policy = r.get("policy")
    details = r.get("details") or {}
    return {
        "index": r.get("index", 0), "file_id": r.get("file_id"), "name": r.get("name"),
        "fields": r["fields"], "flagged": flagged, "reasons": reasons,
        "include": r.get("include", True), "note": r.get("requester_note"),
        "items": [{k: i.get(k) for k in ("name", "quantity", "amount", "kind")} for i in (r.get("items") or [])[:MAX_ITEMS]],
        "headcount": r.get("headcount"), "evidence_kind": r.get("evidence_kind"),
        "details": {k: details.get(k) for k in ("pay_time", "pay_method", "printed_headcount") if details.get(k)},
        "facts": _facts(r),
        "verdict": (policy or {}).get("verdict"),
        "needed_facts": (policy or {}).get("needed_facts") or [],
        "gms_fields": (r.get("verify") or {}).get("gms_fields"),
        "duplicate_of": r.get("duplicate_of"),
        "duplicate_prev": r.get("duplicate_prev"),          # 다른 실행에서 이미 청구 {run_id, state, doc_no}
    }


class HumanConfirmNode(BaseNode):
    node_type = "human_confirm"
    name = "사람 확인"
    category = "human"
    description = "AI가 추출한 값·규정 검토 결과를 사람이 확인하고 필요하면 수정 (여러 장은 장마다, 고친 장은 다시 검토)"
    config_fields = [
        {"key": "message", "label": "안내 문구", "type": "text", "required": False},
        {"key": "extraction", "label": "확인할 결과 노드 (예: {{n3}})", "type": "ref", "required": False},
    ]
    branches = ["확인", "반려"]
    output_vars = [
        {"key": "fields", "label": "확정 값 (첫 장)", "type": "object"},
        {"key": "receipts", "label": "장별 확정 결과", "type": "list"},
        {"key": "edited_fields", "label": "수정된 항목", "type": "list"},
        {"key": "comment", "label": "의견", "type": "text"},
    ]

    def run(self, ctx: NodeContext) -> NodeResult:
        # 바인딩된 노드 출력이 있으면 그것, 없으면 가장 최근의 추출·검증 결과
        extraction = ctx.config.get("extraction") or latest_output(ctx.outputs, "fields")
        if not isinstance(extraction, dict) or "fields" not in extraction:
            raise ValueError("확인할 추출 결과가 없습니다 (앞에 추출 노드가 필요합니다)")
        model_fields = extraction["fields"]
        receipts = extraction.get("receipts") or []
        multi = len(receipts) > 1

        # 여기서 실행이 멈춤. 재개되면 사용자의 응답이 decision으로 돌아옴
        # (재개 시 노드가 처음부터 다시 실행되므로, 이 위에는 DB 저장 같은 부작용을 두지 않음)
        payload = {
            "type": "human_confirm",
            "message": ctx.config.get("message") or (
                "걸린 영수증을 확인해 주세요" if multi else "추출된 값을 확인해 주세요"),
            "fields": model_fields,
            "reasons": extraction.get("reasons", []),       # 왜 사람 확인으로 왔는지
            "gms_fields": extraction.get("gms_fields"),     # GMS가 읽은 값 (참고용)
        }
        if receipts:
            payload["mode"] = "policy" if any(r.get("policy") for r in receipts) else "extraction"
            payload["receipts"] = [compact(r) for r in receipts]
        decision = interrupt(payload)

        # 반려: 값을 확정하지 않으므로 피드백은 저장하지 않음
        if not decision.get("approved"):
            return NodeResult(
                output={"fields": model_fields, "edited_fields": [], "comment": decision.get("comment"),
                        "receipts": receipts, "count": len(receipts)},
                branch="반려",
            )

        if not receipts:                                   # 예전 한 건 흐름 (receipts 없는 추출 결과)
            final_fields = {**model_fields, **(decision.get("data") or {})}
            edited = [k for k, v in final_fields.items() if v != model_fields.get(k)]
            self._feedback(ctx, extraction, [(None, model_fields, final_fields, edited)])
            return NodeResult(output={"fields": final_fields, "edited_fields": edited, "comment": decision.get("comment")},
                              branch="확인")

        # 장마다: 고친 값·사실·포함 여부·설명 반영
        answers = {a.get("index"): a for a in decision.get("receipts") or [] if isinstance(a, dict)}
        if not multi and not answers and decision.get("data"):     # 한 장 화면(예전 방식)의 응답
            answers = {receipts[0].get("index", 0): {"data": decision["data"]}}
        out, labels, recheck = [], [], []
        for r in receipts:
            a = answers.get(r.get("index", 0)) or {}
            fields = {**r["fields"], **(a.get("data") or {})}
            edited = [k for k, v in fields.items() if v != r["fields"].get(k)]
            facts = {**(r.get("facts") or {}), **{k: v for k, v in (a.get("facts") or {}).items() if k in FACT_KEYS}}
            changed_facts = facts != (r.get("facts") or {})
            item = {**r, "fields": fields, "facts": facts, "include": bool(a.get("include", r.get("include", True))),
                    "reviewed": True}
            if a.get("note"):
                item["requester_note"] = str(a["note"])[:500]
            if edited:
                item["edited_fields"] = edited
            out.append(item)
            flagged, _ = _flag(r)
            if flagged or edited:
                labels.append((r, r["fields"], fields, edited))
            if r.get("policy") and item["include"] and (edited or changed_facts):
                recheck.append(item)

        # 규정 검토 뒤의 확인: 고친 장만 다시 검토 (그래도 걸리면 결재에서 건별 문서)
        if recheck:
            from nodes.impl.policy_check import policy_summary, review_receipts   # 무거운 RAG 모듈은 필요할 때만
            attendance = latest_output(ctx.outputs, "by_date") or latest_output(ctx.outputs, "work_date")
            results = review_receipts("영수증", recheck, {}, ctx.inputs, attendance)
            for item, res in zip(recheck, results):
                item["policy"] = {**policy_summary(res), "rechecked": True,
                                  "previous_verdict": (item.get("policy") or {}).get("verdict")}

        self._feedback(ctx, extraction, labels)
        included = [x for x in out if x["include"]] or out
        edited_all = sorted({f"No.{x.get('index', 0) + 1} {k}" if multi else k for x in out for k in x.get("edited_fields", [])})
        return NodeResult(
            output={"fields": included[0]["fields"], "edited_fields": edited_all, "comment": decision.get("comment"),
                    "doc_type": extraction.get("doc_type"), "receipts": out, "count": len(out),
                    "included_count": sum(1 for x in out if x["include"]), "excluded": extraction.get("excluded", [])},
            branch="확인",
        )

    @staticmethod
    def _feedback(ctx: NodeContext, extraction: dict, labels: list[tuple]) -> None:
        """모델이 추출한 값일 때만 학습 라벨로 저장 (사람이 본 장·고친 장)
        (규정 검토 결과처럼 이미 확정된 값을 다시 확인하는 경우는 model_version이 없음 → 저장 안 함)"""
        if not extraction.get("model_version") or not labels:
            return
        with SessionLocal() as db:
            for r, model, final, edited in labels:
                meta = {} if r is None else {"_receipt": {"index": r.get("index", 0), "file_id": r.get("file_id")}}
                db.add(RunFeedback(
                    run_id=ctx.run_id, node_id=ctx.node_id,
                    doc_type=extraction.get("doc_type"),
                    model_version=extraction.get("model_version"),
                    model_output={**model, **meta}, final_output={**final, **meta},
                    edited_fields=edited, label_source="human",
                ))
            db.commit()
