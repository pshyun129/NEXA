"""규정 검토 노드: 추출 결과 + 실행 입력(사용 목적 등) → 사내 규정 RAG 판정 → 준수 / 위반 / 확인 필요.

분기 의미
  준수      : 판정한 조항이 전부 준수 (인용 검증·숫자 비교까지 통과) → 다음 단계로 자동 진행
  위반      : 규정 위반 소지. 자동 반려가 아님 → 사유와 함께 사람 확인으로 보내는 것을 권장
              (예: 정산 기한이 지났어도 팀장 사유서가 있으면 정산 가능 — 경비 규정 제15조③)
  확인 필요 : 판정에 필요한 정보가 없음 → needed_facts를 사람에게 보여주고 확인
  여러 장이면 장마다 판정하고, 하나라도 위반 → 위반, 하나라도 확인 필요 → 확인 필요 (장별 결과는 receipts)

추가 사실(사용 목적·참석 인원 등)을 가져오는 순서
  1) 그 장에 사람이 적은 값 (사람 확인 노드의 receipts[i].facts, 실행 입력 receipts의 같은 파일 줄)
  2) 노드 설정값 (직접 입력 또는 {{n3.xxx}} 참조) — 모든 장에 같이 적용
  3) 실행 입력 폼에서 같은 키 (purpose, headcount ...) 또는 한글 이름 (사용 목적 ...)
  4) 영수증에서 찾은 값 (결제 시각·결제 수단·찍힌 인원·품목) — ai/rag/policy.receipt_facts
"""
from concurrent.futures import ThreadPoolExecutor

from ai.rag.policy import PURPOSES, check_receipt
from core.metrics import POLICY_CHECKS
from nodes.base import BaseNode, NodeContext, NodeResult, Progress, latest_output

# 문서 종류 → 검토 함수 (새 문서 종류는 ai/rag/policy.py에 check_xxx를 만들고 여기에 한 줄 추가)
CHECKERS = {
    "영수증": check_receipt,
}
MAX_WORKERS = 6         # 장 동시 검토 수 (장 안에서도 LLM 조항은 4개씩 동시)

# (설정·입력 키, 규정 용어 이름, 값 종류, 화면 이름)
FACTS = [
    ("purpose", "사용 목적", "purpose", f"사용 목적 ({' / '.join(PURPOSES)})"),
    ("headcount", "참석 인원", "int", "참석 인원"),
    ("attendees", "참석자", "list", "참석자 이름 (쉼표로 구분)"),
    ("pay_time", "결제 시각", "text", "결제 시각 (예: 19:40)"),
    ("pay_method", "결제 수단", "text", "결제 수단 (법인카드 / 개인카드 / 현금)"),
    ("items", "구매 품목", "list", "구매 품목 (쉼표로 구분)"),
    ("reason", "사용 사유", "text", "사용 사유 (야간·주말·공휴일 사용 시)"),
]
VERDICTS = ["위반", "확인 필요", "준수"]


def _convert(value, kind: str):
    """입력값을 판정에 쓰는 형태로. 바꿀 수 없으면 None (= 모르는 사실 → 추측하지 않고 '확인 필요')"""
    if value is None or value == "" or value == []:
        return None
    if kind == "int":
        try:
            return int(str(value).replace(",", "").replace("명", "").strip())
        except ValueError:
            return None
    if kind == "list":
        items = value if isinstance(value, list) else str(value).replace("\n", ",").split(",")
        return [str(x).strip() for x in items if str(x).strip()] or None
    if kind == "purpose":
        text = str(value).strip()
        if text in PURPOSES:
            return text
        # "야근 식대", "팀 회식" 처럼 적어도 목록의 이름이 들어 있으면 인정, 아니면 '기타'
        return next((p for p in PURPOSES if p in text), "기타")
    return str(value).strip()


def collect_facts(config: dict, inputs: dict, attendance: dict | None = None, receipt_date: str | None = None,
                  per_receipt: dict | None = None) -> dict:
    """그 장에 적은 값 → 노드 설정 → 실행 입력 순서로 추가 사실을 모음 (규정 용어 이름을 키로)
    attendance: 근태 조회 노드 출력. 영수증 날짜와 같은 날의 기록만 사용 (다른 날 기록으로 판정하면 안 됨)"""
    extra = {}
    per_receipt = per_receipt or {}
    for key, name, kind, _ in FACTS:
        raw = per_receipt.get(key, per_receipt.get(name))
        if raw in (None, "", []):
            raw = config.get(key)
        if raw in (None, ""):
            raw = inputs.get(key, inputs.get(name))
        value = _convert(raw, kind)
        if value is not None:
            extra[name] = value
    day = attendance_for(attendance, receipt_date)
    if day:
        if day.get("clock_out"):
            extra["퇴근 시각"] = day["clock_out"]
        if day.get("is_holiday") is not None:
            extra["회사 휴일 여부"] = bool(day["is_holiday"])
    return extra


def attendance_for(attendance: dict | None, receipt_date: str | None) -> dict | None:
    """근태 조회 결과에서 그 날짜의 기록 (여러 날짜를 조회했으면 by_date에서)"""
    if not isinstance(attendance, dict) or not receipt_date:
        return None
    by_date = attendance.get("by_date") or {}
    if receipt_date in by_date:
        return by_date[receipt_date]
    return attendance if attendance.get("work_date") == receipt_date else None


def receipt_inputs(inputs: dict, receipt: dict) -> dict:
    """실행 입력의 영수증별 줄(inputs.receipts) 중 이 장의 것: 파일 ID → 파일 이름 → 순번"""
    rows = inputs.get("receipts")
    if not isinstance(rows, list):
        return {}
    for key, rkey in (("file_id", "file_id"), ("fileId", "file_id"), ("name", "name")):
        value = receipt.get(rkey)
        if value is not None:
            row = next((x for x in rows if isinstance(x, dict) and x.get(key) == value), None)
            if row:
                return row
    idx = receipt.get("index")
    row = next((x for x in rows if isinstance(x, dict) and x.get("index") == idx), None)
    return row or {}


def summarize(result: dict) -> list[str]:
    """사람 확인 화면에 보여줄 사유 목록 (human_confirm 노드가 reasons를 그대로 표시)"""
    reasons = []
    for f in result["findings"]:
        if f["verdict"] == "위반":
            detail = "; ".join(f["checks"]) or f["reason"]
            reasons.append(f"위반 · {f['label']}: {detail}")
        elif f["verdict"] == "확인 필요":
            reasons.append(f"확인 필요 · {f['label']}: {f['reason']}")
    if result.get("needed_facts"):
        reasons.append(f"추가로 필요한 정보: {', '.join(result['needed_facts'])}")
    if not result["findings"] and result.get("reason"):
        reasons.append(result["reason"])
    return reasons


def policy_summary(res: dict) -> dict:
    """장별 결과에 붙이는 규정 검토 요약 (receipts[i].policy)"""
    return {"verdict": res["verdict"], "reasons": summarize(res), "needed_facts": res["needed_facts"],
            "findings": res["findings"], "known_facts": res["known_facts"], "timing": res.get("timing")}


def review_receipts(doc_type: str, receipts: list[dict], config: dict, inputs: dict, attendance: dict | None,
                    progress: Progress | None = None) -> list[dict]:
    """장마다 규정 검토 → 검토 결과(check_receipt 반환값) 목록. 노드·사람 확인의 다시 검토·미리보기 API가 같이 씀"""
    checker = CHECKERS.get(doc_type)
    if checker is None:
        raise ValueError(f"규정 검토를 지원하지 않는 문서 종류입니다: {doc_type}")

    def check_one(r: dict) -> dict:
        fields = r["fields"]
        per = {**receipt_inputs(inputs, r), **(r.get("facts") or {})}     # 사람 확인에서 고친 값이 마지막
        facts = collect_facts(config, inputs, attendance, fields.get("date"), per)
        result = checker(fields, facts, receipt=r)
        POLICY_CHECKS.labels(doc_type, result["verdict"], result["judge_version"]).inc()
        if progress:
            progress.tick()
        return result

    if len(receipts) == 1:
        return [check_one(receipts[0])]
    with ThreadPoolExecutor(max_workers=MAX_WORKERS) as pool:
        return list(pool.map(check_one, receipts))


class PolicyCheckNode(BaseNode):
    node_type = "policy_check"
    name = "규정 검토"
    category = "ai"
    description = "사내 규정(DOCS/regulations)에서 관련 조항을 찾아 준수 여부를 판정 (근거 조항 인용 + 숫자는 코드 비교). 여러 장은 장마다"
    config_fields = [
        {"key": "doc_type", "label": "문서 종류", "type": "select", "options": list(CHECKERS), "required": True},
        {"key": "extraction", "label": "검토할 추출 결과 노드 (예: {{n5 || n4}}, 비우면 가장 최근)", "type": "ref",
         "required": False},
        {"key": "attendance", "label": "근태 조회 노드 (예: {{n4}}, 비우면 가장 최근 · 영수증 날짜와 같은 날만 사용)",
         "type": "ref", "required": False},
        *[{"key": key, "label": f"{label} — 비우면 실행 입력의 {key} (모든 장에 같이 적용)", "type": "text",
           "required": False} for key, _, _, label in FACTS],
    ]
    branches = ["준수", "위반", "확인 필요"]
    output_vars = [
        {"key": "verdict", "label": "판정", "type": "text"},
        {"key": "reasons", "label": "사유 목록", "type": "list"},
        {"key": "needed_facts", "label": "추가로 필요한 정보", "type": "list"},
        {"key": "findings", "label": "조항별 판정 (첫 장)", "type": "list"},
        {"key": "system_rules", "label": "시스템이 처리하는 규정", "type": "list"},
        {"key": "fields", "label": "검토한 추출 결과 (첫 장)", "type": "object"},
        {"key": "receipts", "label": "장별 결과 (판정 포함)", "type": "list"},
        {"key": "counts", "label": "판정별 장 수", "type": "object"},
        {"key": "known_facts", "label": "검토에 쓴 사실 (품목·참석자·사유·1인당 금액 등)", "type": "object"},
        {"key": "doc_type", "label": "문서 종류", "type": "text"},
        {"key": "index_version", "label": "규정 색인 버전", "type": "text"},
        {"key": "judge_version", "label": "판정 모델 버전", "type": "text"},
    ]

    def run(self, ctx: NodeContext) -> NodeResult:
        doc_type = ctx.config["doc_type"]
        if doc_type not in CHECKERS:
            raise ValueError(f"규정 검토를 지원하지 않는 문서 종류입니다: {doc_type}")

        # 바인딩된 노드 출력이 있으면 그것, 없으면 가장 최근의 추출·검증·사람 확인 결과
        extraction = ctx.config.get("extraction") or latest_output(ctx.outputs, "fields")
        if not isinstance(extraction, dict) or not isinstance(extraction.get("fields"), dict):
            raise ValueError("검토할 추출 결과가 없습니다 (앞에 추출 노드가 필요합니다)")
        attendance = ctx.config.get("attendance") or latest_output(ctx.outputs, "work_date")

        # 장 목록: 사람이 정산에서 뺀 장(include=False)은 검토하지 않음
        receipts = [r for r in (extraction.get("receipts") or [{"index": 0, "fields": extraction["fields"]}])
                    if r.get("include", True)]
        if not receipts:
            raise ValueError("검토할 영수증이 없습니다 (모든 장이 정산에서 빠졌습니다)")

        results = review_receipts(doc_type, receipts, ctx.config, ctx.inputs, attendance,
                                  Progress(ctx, len(receipts), "규정 검토"))
        out_receipts = [{**r, "policy": policy_summary(res)} for r, res in zip(receipts, results)]
        counts = {v: sum(1 for x in results if x["verdict"] == v) for v in VERDICTS}
        overall = next((v for v in VERDICTS if counts[v]), "준수")
        first = results[0]
        single = len(results) == 1
        reasons = summarize(first) if single else [
            f"No.{r.get('index', i) + 1} {why}" for i, (r, res) in enumerate(zip(receipts, results)) for why in summarize(res)]
        needed = first["needed_facts"] if single else sorted({n for res in results for n in res["needed_facts"]})

        # model_version은 일부러 넘기지 않음: 뒤에 사람 확인 노드가 와도
        # 이 값은 모델 추출 결과가 아니라 이미 확정된 값이라 학습 라벨(run_feedback)로 다시 저장되면 안 됨
        out = {
            "verdict": overall,
            "reasons": reasons,
            "needed_facts": needed,
            "findings": first["findings"],
            "system_rules": first["system_rules"],
            "fields": receipts[0]["fields"],
            "doc_type": doc_type,
            "known_facts": first["known_facts"],
            "retrieval": first["retrieval"],          # 어떤 질문으로 어떤 조항을 찾았는지 (추적용, 첫 장)
            "index_version": first["index_version"],
            "judge_version": first["judge_version"],
            "timing": first.get("timing") if single else {
                "receipts": len(results),
                "llm_clauses": sum((x.get("timing") or {}).get("llm_clauses", 0) for x in results),
                "code_clauses": sum((x.get("timing") or {}).get("code_clauses", 0) for x in results),
            },
            "receipts": out_receipts, "count": len(out_receipts), "counts": counts,
            "excluded": extraction.get("excluded", []),
        }
        return NodeResult(output=out, branch=overall)
