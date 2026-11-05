"""규정 검토 노드: 추출 결과 + 실행 입력(사용 목적 등) → 사내 규정 RAG 판정 → 준수 / 위반 / 확인 필요.

분기 의미
  준수      : 판정한 조항이 전부 준수 (인용 검증·숫자 비교까지 통과) → 다음 단계로 자동 진행
  위반      : 규정 위반 소지. 자동 반려가 아님 → 사유와 함께 사람 확인으로 보내는 것을 권장
              (예: 정산 기한이 지났어도 팀장 사유서가 있으면 정산 가능 — 경비 규정 제15조③)
  확인 필요 : 판정에 필요한 정보가 없음 → needed_facts를 사람에게 보여주고 확인

추가 사실(사용 목적·참석 인원 등)을 가져오는 순서
  1) 노드 설정값 (직접 입력 또는 {{n3.xxx}} 참조)
  2) 비어 있으면 실행 입력 폼에서 같은 키 (purpose, headcount ...) 또는 한글 이름 (사용 목적 ...)
"""
from ai.rag.policy import PURPOSES, check_receipt
from core.metrics import POLICY_CHECKS
from nodes.base import BaseNode, NodeContext, NodeResult, latest_output

# 문서 종류 → 검토 함수 (새 문서 종류는 ai/rag/policy.py에 check_xxx를 만들고 여기에 한 줄 추가)
CHECKERS = {
    "영수증": check_receipt,
}

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


def collect_facts(config: dict, inputs: dict) -> dict:
    """노드 설정 → 실행 입력 순서로 추가 사실을 모음 (규정 용어 이름을 키로)"""
    extra = {}
    for key, name, kind, _ in FACTS:
        raw = config.get(key)
        if raw in (None, ""):
            raw = inputs.get(key, inputs.get(name))
        value = _convert(raw, kind)
        if value is not None:
            extra[name] = value
    return extra


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


class PolicyCheckNode(BaseNode):
    node_type = "policy_check"
    name = "규정 검토"
    category = "ai"
    description = "사내 규정(DOCS/regulations)에서 관련 조항을 찾아 준수 여부를 판정 (근거 조항 인용 + 숫자는 코드 비교)"
    config_fields = [
        {"key": "doc_type", "label": "문서 종류", "type": "select", "options": list(CHECKERS), "required": True},
        {"key": "extraction", "label": "검토할 추출 결과 노드 (예: {{n5 || n4}}, 비우면 가장 최근)", "type": "ref",
         "required": False},
        *[{"key": key, "label": f"{label} — 비우면 실행 입력의 {key}", "type": "text", "required": False}
          for key, _, _, label in FACTS],
    ]
    branches = ["준수", "위반", "확인 필요"]
    output_vars = [
        {"key": "verdict", "label": "판정", "type": "text"},
        {"key": "reasons", "label": "사유 목록", "type": "list"},
        {"key": "needed_facts", "label": "추가로 필요한 정보", "type": "list"},
        {"key": "findings", "label": "조항별 판정", "type": "list"},
        {"key": "system_rules", "label": "시스템이 처리하는 규정", "type": "list"},
        {"key": "fields", "label": "검토한 추출 결과", "type": "object"},
        {"key": "doc_type", "label": "문서 종류", "type": "text"},
        {"key": "index_version", "label": "규정 색인 버전", "type": "text"},
        {"key": "judge_version", "label": "판정 모델 버전", "type": "text"},
    ]

    def run(self, ctx: NodeContext) -> NodeResult:
        doc_type = ctx.config["doc_type"]
        checker = CHECKERS.get(doc_type)
        if checker is None:
            raise ValueError(f"규정 검토를 지원하지 않는 문서 종류입니다: {doc_type}")

        # 바인딩된 노드 출력이 있으면 그것, 없으면 가장 최근의 추출·검증·사람 확인 결과
        extraction = ctx.config.get("extraction") or latest_output(ctx.outputs, "fields")
        if not isinstance(extraction, dict) or not isinstance(extraction.get("fields"), dict):
            raise ValueError("검토할 추출 결과가 없습니다 (앞에 추출 노드가 필요합니다)")

        result = checker(extraction["fields"], collect_facts(ctx.config, ctx.inputs))
        POLICY_CHECKS.labels(doc_type, result["verdict"], result["judge_version"]).inc()

        # model_version은 일부러 넘기지 않음: 뒤에 사람 확인 노드가 와도
        # 이 값은 모델 추출 결과가 아니라 이미 확정된 값이라 학습 라벨(run_feedback)로 다시 저장되면 안 됨
        out = {
            "verdict": result["verdict"],
            "reasons": summarize(result),
            "needed_facts": result["needed_facts"],
            "findings": result["findings"],
            "system_rules": result["system_rules"],
            "fields": extraction["fields"],
            "doc_type": doc_type,
            "known_facts": result["known_facts"],
            "retrieval": result["retrieval"],          # 어떤 질문으로 어떤 조항을 찾았는지 (추적용)
            "index_version": result["index_version"],
            "judge_version": result["judge_version"],
        }
        return NodeResult(output=out, branch=result["verdict"])