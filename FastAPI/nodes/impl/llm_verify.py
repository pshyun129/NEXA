"""자동 검증 노드: 규칙 + 날짜 상식 검사 + OCR 신뢰도 + GMS 독립 추출 비교로 자동 확정 여부 판단."""
import re
from datetime import date, datetime, timedelta, timezone

from ai.llm import gms_status
from ai.modules.receipt import extract_receipt_with_gms
from core.database import SessionLocal
from core.metrics import VERIFY
from nodes.base import BaseNode, NodeContext, NodeResult, latest_output
from runs.models import RunFeedback

# 문서 종류 → GMS 검증용 추출 함수
GMS_EXTRACTORS = {
    "영수증": extract_receipt_with_gms,
}

# 서버(EC2)가 UTC여도 '오늘'은 한국 날짜 기준 (Windows에 tzdata가 없어도 되게 고정 오프셋)
KST = timezone(timedelta(hours=9))
MAX_AGE_DAYS = 180  # 기본값: 이보다 오래된 거래일은 사람 확인 (정산 기한 30일 판정은 규정 검토 노드 몫)

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


class LlmVerifyNode(BaseNode):
    node_type = "llm_verify"
    name = "자동 검증"
    category = "ai"
    description = "업무 규칙·날짜 상식·OCR 신뢰도·GMS 교차 추출로 자동 확정, 애매하면 사람 확인으로"
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
        {"key": "fields", "label": "추출 결과", "type": "object"},
        {"key": "doc_type", "label": "문서 종류", "type": "text"},
        {"key": "problems", "label": "규칙 위반 항목", "type": "list"},
        {"key": "model_version", "label": "모델 버전", "type": "text"},
        {"key": "reasons", "label": "사람 확인 사유", "type": "list"},
        {"key": "gms_fields", "label": "GMS 추출 결과", "type": "object"},
    ]

    def run(self, ctx: NodeContext) -> NodeResult:
        # 바인딩된 노드 출력이 있으면 그것, 없으면 가장 최근 것
        extraction = ctx.config.get("extraction") or latest_output(ctx.outputs, "fields")
        if not isinstance(extraction, dict) or "fields" not in extraction:
            raise ValueError("검증할 추출 결과가 없습니다 (앞에 추출 노드가 필요합니다)")
        ocr = ctx.config.get("ocr") or latest_output(ctx.outputs, "confidence")

        fields = extraction["fields"]
        doc_type = extraction.get("doc_type")
        reasons = []

        # ① 업무 규칙 (structured_extract가 검사한 결과)
        for name in extraction.get("problems", []):
            reasons.append(f"규칙 위반: {name}")

        # ② 날짜 상식 검사 (날짜 항목이 있는 문서만)
        if fields.get("date"):
            max_age = int(ctx.config.get("max_age_days") or MAX_AGE_DAYS)
            reasons += date_problems(fields["date"], (ocr or {}).get("text"), max_age)

        # ③ OCR 신뢰도
        threshold = float(ctx.config.get("min_confidence") or 0.9)
        if ocr is not None and ocr.get("confidence") is not None and ocr["confidence"] < threshold:
            reasons.append(f"OCR 신뢰도 낮음: {ocr['confidence']}")

        # ④ GMS 독립 추출과 비교 (앞에서 이미 걸렸으면 GMS 호출 생략 → 비용 절약)
        gms_fields = None
        use_gms = ctx.config.get("use_gms", "사용") != "사용 안 함"
        if use_gms and not reasons:
            if gms_status() != "configured" or doc_type not in GMS_EXTRACTORS:
                reasons.append("GMS 검증 불가")
            else:
                try:
                    gms_fields = GMS_EXTRACTORS[doc_type]((ocr or {}).get("text", "")).model_dump()
                    diff = [k for k in fields if _norm(fields[k]) != _norm(gms_fields.get(k))]
                    reasons += [f"GMS와 불일치: {k}" for k in diff]
                except Exception as e:
                    reasons.append(f"GMS 검증 실패: {e.__class__.__name__}")

        out = {
            "fields": fields, "doc_type": doc_type,
            "model_version": extraction.get("model_version"),
            "problems": extraction.get("problems", []),
            "reasons": reasons, "gms_fields": gms_fields,
        }

        if reasons:
            VERIFY.labels(extraction.get("doc_type") or "?", extraction.get("model_version") or "?", "human").inc()
            return NodeResult(output=out, branch="사람 확인")

        # 모두 통과 → 자동 확정, 피드백 저장 (라벨 출처: 로컬·GMS 일치)
        with SessionLocal() as db:
            db.add(RunFeedback(
                run_id=ctx.run_id, node_id=ctx.node_id, doc_type=doc_type,
                model_version=extraction.get("model_version"),
                model_output=fields, final_output=fields, edited_fields=[],
                label_source="llm_agree",
            ))
            db.commit()
        VERIFY.labels(extraction.get("doc_type") or "?", extraction.get("model_version") or "?", "auto").inc()
        return NodeResult(output=out, branch="자동 확정")