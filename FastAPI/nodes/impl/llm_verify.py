"""자동 검증 노드: 장마다 규칙 + 날짜 상식 + OCR 신뢰도 + 싼 검증(근거·산수·정규식)으로 판단하고,
애매한 장만 GMS 교차 추출로 한 번 더 본다. 통과한 장은 자동 확정, 하나라도 걸리면 사람 확인으로.

여러 장(receipts)이 와도 장별로 따로 판단한다:
  ① 업무 규칙·중복·날짜·OCR 신뢰도에 걸린 장 → 사람 확인 (GMS 안 부름)
  ② 싼 검증 통과 → 자동 확정 (label_source=rule_agree). 단 일부(기본 5%)는 GMS로 감사(audit)해 싼 검증의 놓침을 잰다
  ③ 싼 검증에 걸린 장 → GMS 독립 추출과 핵심 항목(store·total·date) 비교. 일치하면 자동 확정(llm_agree), 아니면 사람 확인
"""
import hashlib
import re
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime, timedelta, timezone

from ai.llm import gms_status
from ai.modules.receipt import CORE_FIELDS, extract_receipt_with_gms
from ai.receipts.checks import cheap_checks
from ai.receipts.parse import parse_details
from core.database import SessionLocal
from core.metrics import VERIFY, VERIFY_GMS
from nodes.base import BaseNode, NodeContext, NodeResult, Progress, latest_output
from runs.models import RunFeedback

# 문서 종류 → GMS 검증용 추출 함수
GMS_EXTRACTORS = {
    "영수증": extract_receipt_with_gms,
}

# 서버(EC2)가 UTC여도 '오늘'은 한국 날짜 기준 (Windows에 tzdata가 없어도 되게 고정 오프셋)
KST = timezone(timedelta(hours=9))
MAX_AGE_DAYS = 180  # 기본값: 이보다 오래된 거래일은 사람 확인 (정산 기한 30일 판정은 규정 검토 노드 몫)
AUDIT_RATE = 0.05   # 싼 검증을 통과한 장 중 GMS로 감사할 비율
GMS_WORKERS = 4     # GMS 동시 호출 수 (외부 API 한도 보호)
UNGROUNDED_TOTAL = "원문에 없는 금액"   # checks.cheap_checks의 사유 머리말

# OCR 텍스트 속 날짜: 2026-11-02 / 2026.11.02 / 2026/11/02 / 26-11-02 / 2026년 11월 2일
#   '월' 자리에 OCR이 '일'로 잘못 읽은 경우도 허용, 뒤에 시각이 붙어 있어도 됨 (2026-10-0214:08)
DATE_IN_TEXT = re.compile(r"(?<!\d)(\d{4}|\d{2})\s*[-./년]\s*(\d{1,2})\s*[-./월일]\s*(\d{1,2})(?!\d{2}[-./])")
COMPACT_DATE = re.compile(r"(?<!\d)(20\d{2})(\d{2})(\d{2})(?!\d)")   # 20261102


def _norm(value):
    """비교용 정규화: 문자열은 공백을 모두 제거 (OCR 띄어쓰기 차이 무시)."""
    return "".join(value.split()) if isinstance(value, str) else value


def dates_in_text(text: str) -> set[str]:
    """OCR 텍스트에 실제로 보이는 날짜들 (YYYY-MM-DD)"""
    found = set()
    for y, m, d in DATE_IN_TEXT.findall(text or "") + COMPACT_DATE.findall(text or ""):
        y = int(y) + (2000 if len(y) == 2 else 0)
        try:
            found.add(date(y, int(m), int(d)).isoformat())
        except ValueError:      # 13월·32일 같은 건 날짜가 아님
            pass
    return found


def date_problems(value, ocr_text: str | None, max_age_days: int = MAX_AGE_DAYS,
                  today: date | None = None) -> list[str]:
    """추출한 날짜의 상식 검사. LLM이 안 보이는 날짜를 지어내는 경우(온도 0에서도 실행마다 바뀜)를 막음
      - 형식: YYYY-MM-DD가 아니면 문제
      - 범위: 미래 날짜, 너무 오래된 날짜
      - 근거: OCR 텍스트에 그 날짜가 없으면 지어낸 값으로 봄
    """
    today = today or datetime.now(KST).date()
    try:
        d = date.fromisoformat(str(value).strip())
    except ValueError:
        return [f"날짜 형식 오류: {value}"]
    problems = []
    if d > today:
        problems.append(f"미래 날짜: {d}")
    elif (today - d).days > max_age_days:
        problems.append(f"너무 오래된 날짜: {d} ({(today - d).days}일 전)")
    if ocr_text and d.isoformat() not in dates_in_text(ocr_text):
        problems.append(f"OCR 텍스트에 없는 날짜: {d}")
    return problems


def audit_pick(run_id, index: int, rate: float = AUDIT_RATE) -> bool:
    """감사 대상인지: 실행·장 번호로 정해지는 표본 (다시 돌려도 같은 장이 뽑힘)"""
    h = int(hashlib.sha256(f"{run_id}:{index}".encode()).hexdigest()[:8], 16)
    return (h % 10_000) < rate * 10_000


def gms_compare(doc_type: str, text: str, fields: dict) -> tuple[dict | None, list[str]]:
    """GMS로 다시 뽑아 핵심 항목 비교 → (GMS 결과, 불일치 사유)"""
    try:
        gms = GMS_EXTRACTORS[doc_type](text).model_dump()
    except Exception as e:
        return None, [f"GMS 검증 실패: {e.__class__.__name__}"]
    gms.pop("items", None)
    keys = CORE_FIELDS if doc_type == "영수증" else list(fields)
    return gms, [f"GMS와 불일치: {k}" for k in keys if _norm(fields.get(k)) != _norm(gms.get(k))]


def prior_claim_text(p: dict) -> str:
    """다른 실행에서 이미 청구한 영수증 안내 문구"""
    doc = f", 문서 {p['doc_no']}" if p.get("doc_no") else ""
    return f"이미 정산 신청한 영수증: 실행 #{p['run_id']} ({p.get('state')}{doc})"


def pre_checks(r: dict, doc_type: str, ocr_conf, threshold: float, max_age: int) -> tuple[list[str], list[str]]:
    """GMS 전에 하는 검사 → (확정 사유: 바로 사람 확인, 애매 사유: GMS로 한 번 더)"""
    fields, text = r["fields"], r.get("text") or ""
    hard = [f"규칙 위반: {p}" for p in r.get("problems", []) if p != "duplicate"]
    if r.get("duplicate_of") is not None:
        hard.append(f"중복 영수증: No.{r['duplicate_of'] + 1}과 같은 결제")
    if r.get("duplicate_prev"):
        hard.append(prior_claim_text(r["duplicate_prev"]))
    if fields.get("date"):
        hard += date_problems(fields["date"], text, max_age)
    if ocr_conf is not None and ocr_conf < threshold:
        hard.append(f"OCR 신뢰도 낮음: {ocr_conf}")
    soft = []
    if doc_type == "영수증" and not hard:
        details = parse_details(text, fields.get("date"))
        soft = cheap_checks(fields, r.get("items"), details, text)
    return hard, soft


class LlmVerifyNode(BaseNode):
    node_type = "llm_verify"
    name = "자동 검증"
    category = "ai"
    description = "업무 규칙·날짜 상식·OCR 신뢰도·원문 대조로 장마다 자동 확정, 애매한 장만 GMS 교차 추출, 걸리면 사람 확인으로"
    config_fields = [
        {"key": "use_gms", "label": "GMS 교차 검증", "type": "select",
         "options": ["사용", "사용 안 함"], "required": False},
        {"key": "min_confidence", "label": "최소 OCR 신뢰도", "type": "number", "required": False},
        {"key": "max_age_days", "label": "허용할 최대 경과일 (기본 180일)", "type": "number", "required": False},
        {"key": "extraction", "label": "검증할 추출 결과 노드 (예: {{n2}})", "type": "ref", "required": False},
        {"key": "ocr", "label": "OCR 노드 (예: {{n1}})", "type": "ref", "required": False},
    ]
    branches = ["자동 확정", "사람 확인"]
    output_vars = [
        {"key": "fields", "label": "추출 결과 (첫 장)", "type": "object"},
        {"key": "receipts", "label": "장별 결과 (검증 포함)", "type": "list"},
        {"key": "count", "label": "장 수", "type": "number"},
        {"key": "doc_type", "label": "문서 종류", "type": "text"},
        {"key": "problems", "label": "규칙 위반 항목", "type": "list"},
        {"key": "model_version", "label": "모델 버전", "type": "text"},
        {"key": "reasons", "label": "사람 확인 사유", "type": "list"},
        {"key": "gms_fields", "label": "GMS 추출 결과 (첫 장)", "type": "object"},
    ]

    def run(self, ctx: NodeContext) -> NodeResult:
        # 바인딩된 노드 출력이 있으면 그것, 없으면 가장 최근 것
        extraction = ctx.config.get("extraction") or latest_output(ctx.outputs, "fields")
        if not isinstance(extraction, dict) or "fields" not in extraction:
            raise ValueError("검증할 추출 결과가 없습니다 (앞에 추출 노드가 필요합니다)")
        ocr = ctx.config.get("ocr") or latest_output(ctx.outputs, "confidence") or {}

        doc_type = extraction.get("doc_type")
        model_version = extraction.get("model_version")
        # 예전 추출 노드 출력(receipts 없음)도 한 장으로 받는다
        receipts = extraction.get("receipts") or [{
            "index": 0, "fields": extraction["fields"], "items": extraction.get("items", []),
            "problems": extraction.get("problems", []), "text": ocr.get("text")}]
        if len(receipts) == 1 and not receipts[0].get("text"):
            receipts[0]["text"] = ocr.get("text")

        threshold = float(ctx.config.get("min_confidence") or 0.9)
        max_age = int(ctx.config.get("max_age_days") or MAX_AGE_DAYS)
        use_gms = ctx.config.get("use_gms", "사용") != "사용 안 함"
        gms_ready = gms_status() == "configured" and doc_type in GMS_EXTRACTORS
        page_conf = {p.get("file_id"): p.get("confidence") for p in ocr.get("pages") or []}

        # ① GMS 없이 하는 검사 (장마다)
        plans = []
        for r in receipts:
            conf = page_conf.get(r.get("file_id"), ocr.get("confidence")) if len(receipts) > 1 else ocr.get("confidence")
            hard, soft = pre_checks(r, doc_type, conf, threshold, max_age)
            if hard:
                purpose = None                                   # 이미 사람 확인 → GMS 안 부름
            elif soft or doc_type != "영수증":
                purpose = "ambiguous"                            # 애매함(또는 싼 검증이 없는 문서) → GMS
            else:
                purpose = "audit" if audit_pick(ctx.run_id, r["index"]) else None
            plans.append({"r": r, "hard": hard, "soft": soft, "purpose": purpose})

        # ② 필요한 장만 GMS (동시에 몇 장씩)
        todo = [p for p in plans if p["purpose"] and use_gms and gms_ready]
        progress = Progress(ctx, len(todo), "교차 검증")

        def compare(p):
            out = gms_compare(doc_type, p["r"].get("text") or "", p["r"]["fields"])
            progress.tick()
            return out
        with ThreadPoolExecutor(max_workers=GMS_WORKERS) as pool:
            for p, (gms, diff) in zip(todo, pool.map(compare, todo)):
                p["gms"], p["diff"] = gms, diff
                VERIFY_GMS.labels(p["purpose"], "disagree" if diff else "agree").inc()

        # ③ 장별 결론
        out_receipts, feedback = [], []
        for p in plans:
            r = p["r"]
            reasons = list(p["hard"])
            label = None
            if not reasons:
                if p["purpose"] == "ambiguous":
                    if not use_gms:
                        reasons += p["soft"]                    # 꺼져 있으면 싼 검증 결과만으로 (없으면 자동 확정)
                        label = None if reasons else "rule_agree"
                    elif not gms_ready:
                        reasons += [*p["soft"], "GMS 검증 불가"]
                    elif p["diff"]:
                        reasons += [*p["soft"], *p["diff"]]
                    elif any(s.startswith(UNGROUNDED_TOTAL) for s in p["soft"]):
                        # 두 모델이 같아도 금액이 원문 어디에도 없으면 돈 문제라 사람 확인
                        reasons += [*p["soft"], "GMS도 같은 금액이지만 원문에서 찾지 못함"]
                    else:
                        label = "llm_agree"                     # 원문 대조는 어긋났지만(예: 상호 OCR 오타) 두 모델이 같은 값
                else:
                    # 싼 검증 통과. 감사에서 GMS와 어긋나면 그 장은 사람 확인 (감사 결과는 지표로도 남음)
                    if p.get("diff"):
                        reasons += [f"표본 감사: {d}" for d in p["diff"]]
                    else:
                        label = "rule_agree"
            route = "auto" if label else "human"
            VERIFY.labels(doc_type or "?", model_version or "?", route).inc()
            if label:
                feedback.append((r, label))
            # 뒤 노드로는 OCR 원문을 넘기지 않는다 (실행 기록이 커지지 않게, 원문은 OCR 노드에 있음)
            item = {k: v for k, v in r.items() if k != "text"}
            item["verify"] = {"route": route, "reasons": reasons, "label_source": label,
                              "gms": p["purpose"] if p.get("gms") is not None or p.get("diff") else None,
                              "gms_fields": p.get("gms")}
            out_receipts.append(item)

        # 자동 확정된 장은 피드백으로 저장 (MLOps 자동화율·감사용). 장 번호·파일은 JSON 안에 같이
        if feedback:
            with SessionLocal() as db:
                for r, label in feedback:
                    meta = {"_receipt": {"index": r.get("index", 0), "file_id": r.get("file_id")}}
                    db.add(RunFeedback(
                        run_id=ctx.run_id, node_id=ctx.node_id, doc_type=doc_type, model_version=model_version,
                        model_output={**r["fields"], **meta}, final_output={**r["fields"], **meta},
                        edited_fields=[], label_source=label,
                    ))
                db.commit()

        single = len(out_receipts) == 1
        first = out_receipts[0]
        reasons = first["verify"]["reasons"] if single else [
            f"No.{x['index'] + 1} {why}" for x in out_receipts for why in x["verify"]["reasons"]]
        human = sum(1 for x in out_receipts if x["verify"]["route"] == "human")
        out = {
            "fields": first["fields"], "doc_type": doc_type, "model_version": model_version,
            "problems": extraction.get("problems", []),
            "reasons": reasons, "gms_fields": first["verify"]["gms_fields"],
            "receipts": out_receipts, "count": len(out_receipts), "excluded": extraction.get("excluded", []),
            "auto_count": len(out_receipts) - human, "human_count": human,
        }
        return NodeResult(output=out, branch="사람 확인" if human else "자동 확정")
