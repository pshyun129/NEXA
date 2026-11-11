"""항목 추출 노드 : OCR 텍스트 -> 문서 종류별 구조화 데이터 (DSPy).

영수증 여러 장(OCR pages가 2장 이상)이면 장마다 동시에 추출해 receipts 목록으로 넘긴다.
  - 장마다: LLM 추출(상호·금액·날짜·품목) + 정규식 값(결제 시각·수단·승인번호·사업자번호·공급가액·부가세·찍힌 인원)
            + 인원 추정 범위 + 중복 영수증 표시
  - 호환: fields·problems·model_version은 1장일 때와 같은 모양(첫 장 기준). 1장 워크플로·기존 참조({{n3.fields}})는 그대로 동작
"""
import re
from concurrent.futures import ThreadPoolExecutor
from datetime import date

from ai.modules.receipt import CORE_FIELDS, RECEIPT_VERSION, extract_receipt
from ai.receipts.headcount import estimate_headcount
from ai.receipts.parse import EVIDENCE_KIND, parse_details
from nodes.base import BaseNode, NodeContext, NodeResult, Progress, latest_output

# 문서 종류 -> (추출 함수, 모델 버전)
# 새 문서 종류는 DSPy 모듈을 만들고 여기에 한 줄 추가
# 모델 버전 = 서버가 불러온 챔피언 버전 (ai/programs/*.meta.json, 없으면 base)
EXTRACTORS = {
    "영수증": (extract_receipt, RECEIPT_VERSION),
}
MAX_WORKERS = 8          # 동시에 추출할 장 수 (vLLM 동시 처리 한도 max-num-seqs 8과 맞춤)
MAX_PAGES = 300          # 한 실행에서 처리할 최대 장 수


def _validate(doc_type: str, fields: dict) -> list[str]:
    """문서 종류별 필수값·형식 검사. 문제가 있는 필드 이름 목록을 돌려준다."""
    problems = []
    if doc_type == "영수증":
        if not fields.get("store"):
            problems.append("store")
        if not isinstance(fields.get("total"), int) or fields["total"] <= 0:
            problems.append("total")
        try:
            date.fromisoformat(fields.get("date") or "")  # YYYY-MM-DD 형식인지
        except ValueError:
            problems.append("date")
    return problems


def _dup_key(r: dict) -> tuple | None:
    """같은 영수증인지 보는 열쇠: 사업자번호+승인번호, 없으면 상호(띄어쓰기 무시)+금액+날짜+결제 시각"""
    d, f = r["details"], r["fields"]
    biz, appr = (d.get("biz_no") or {}).get("value"), (d.get("approval_no") or {}).get("value")
    if biz and appr:
        return ("approval", biz, appr)
    store = re.sub(r"\s+", "", f.get("store") or "")
    if store and f.get("total") and f.get("date"):
        return ("same", store, f["total"], f["date"], (d.get("pay_time") or {}).get("value"))
    return None


def extract_one(doc_type: str, text: str, index: int = 0, page: dict | None = None) -> dict:
    """영수증 한 장 → 추출 결과 한 건 (receipts 목록의 원소)"""
    extract, _ = EXTRACTORS[doc_type]
    data = extract(text).model_dump()               # pydantic 객체 → dict (JSON 저장 가능하게)
    items = data.pop("items", []) if doc_type == "영수증" else []
    fields = {k: data.get(k) for k in CORE_FIELDS} if doc_type == "영수증" else data
    out = {"index": index, "file_id": (page or {}).get("file_id"), "name": (page or {}).get("name"),
           "text": text, "fields": fields, "items": items, "problems": _validate(doc_type, fields)}
    if doc_type == "영수증":
        details = parse_details(text, fields.get("date"))
        pay = details.value("pay_method")
        out["details"] = details.to_dict()
        out["evidence_kind"] = EVIDENCE_KIND.get(pay)      # 결제 수단 → 증빙 구분 (모르면 None)
        out["headcount"] = {
            "printed": details.value("printed_headcount"),   # 영수증에 찍힌 인원 (판정에 써도 됨)
            "estimate": estimate_headcount(items),           # 품목 기준 범위 (도움말·부풀림 감지용, 판정엔 안 씀)
        }
    return out


class StructuredExtractNode(BaseNode):
    node_type = "structured_extract"
    name = "서류 항목 추출"
    category = "ai"
    description = "OCR 텍스트에서 문서 종류에 맞는 항목을 추출 (DSPy + LLM). 영수증 여러 장은 장마다 동시에"
    config_fields = [
        {"key": "doc_type", "label": "문서 종류", "type": "select",
         "options": list(EXTRACTORS.keys()), "required": True},
        {"key": "text", "label": "입력 텍스트 (예: {{n1.text}}, 비우면 가장 최근 텍스트)", "type": "ref",
         "required": False},
    ]
    branches = ["성공", "확인 필요"]
    output_vars = [
        {"key": "fields", "label": "추출 결과 (첫 장)", "type": "object"},
        {"key": "items", "label": "품목 (첫 장)", "type": "list"},
        {"key": "receipts", "label": "장별 추출 결과", "type": "list"},
        {"key": "count", "label": "장 수", "type": "number"},
        {"key": "doc_type", "label": "문서 종류", "type": "text"},
        {"key": "problems", "label": "확인이 필요한 항목", "type": "list"},
        {"key": "model_version", "label": "모델 버전", "type": "text"},
    ]

    def run(self, ctx: NodeContext) -> NodeResult:
        doc_type = ctx.config["doc_type"]
        if doc_type not in EXTRACTORS:
            raise ValueError(f"지원하지 않는 문서 종류입니다: {doc_type}")
        _, model_version = EXTRACTORS[doc_type]

        # 여러 장: OCR의 장별 결과(pages)로 장마다 추출. 분류 노드가 다른 종류로 본 장은 제외
        ocr = latest_output(ctx.outputs, "pages")
        pages = (ocr or {}).get("pages") or []
        classified = latest_output(ctx.outputs, "page_types")
        skip = {p["file_id"]: p["doc_type"] for p in (classified or {}).get("page_types", []) if p.get("doc_type") != doc_type}
        if len(pages) >= 2:
            if len(pages) > MAX_PAGES:
                raise ValueError(f"한 번에 {MAX_PAGES}장까지 처리할 수 있습니다 (받은 장: {len(pages)})")
            todo = [(i, p) for i, p in enumerate(pages) if p.get("file_id") not in skip]
            if not todo:
                raise ValueError(f"{doc_type}로 분류된 장이 없습니다")
            with ThreadPoolExecutor(max_workers=MAX_WORKERS) as pool:
                progress = Progress(ctx, len(todo), "항목 추출")

                def one(ip):
                    out = extract_one(doc_type, ip[1]["text"], ip[0], ip[1])
                    progress.tick()
                    return out
                receipts = list(pool.map(one, todo))
            excluded = [{"index": i, "file_id": p.get("file_id"), "name": p.get("name"),
                         "reason": f"{skip[p.get('file_id')]}로 분류되어 제외"} for i, p in enumerate(pages) if p.get("file_id") in skip]
        else:
            # 한 장: 바인딩된 텍스트가 있으면 그것, 없으면 앞 노드 중 가장 최근 text
            text = ctx.config.get("text") or (latest_output(ctx.outputs, "text") or {}).get("text")
            if not text:
                raise ValueError("추출할 텍스트가 없습니다 (앞에 OCR 노드가 필요합니다)")
            receipts = [extract_one(doc_type, text, 0, pages[0] if pages else None)]
            excluded = []

        # 같은 영수증이 두 번 들어왔으면 뒤의 것을 표시 (이중 청구 방지)
        seen: dict[tuple, int] = {}
        for r in receipts:
            key = _dup_key(r) if doc_type == "영수증" else None
            if key and key in seen:
                r["duplicate_of"] = seen[key]
                r["problems"].append("duplicate")
            elif key:
                seen[key] = r["index"]

        first = receipts[0]
        single = len(receipts) == 1
        problems = first["problems"] if single else [f"No.{r['index'] + 1} {p}" for r in receipts for p in r["problems"]]
        return NodeResult(
            output={"fields": first["fields"], "items": first.get("items", []), "doc_type": doc_type,
                    "problems": problems, "model_version": model_version,
                    "receipts": receipts, "count": len(receipts), "excluded": excluded},
            branch="확인 필요" if problems else "성공",
        )
