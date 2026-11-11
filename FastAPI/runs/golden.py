"""골든셋 모으기: 사람이 확정한 결과(run_feedback, label_source=human) → 평가셋 행.

mlops/export_golden.py(파일로 내보내기)와 runs/golden_sync.py(MLflow로 자동 전송)가 같이 씀.
llm_agree(로컬·GMS 일치)·rule_agree(원문 대조 통과) 자동 확정은 넣지 않음 → 두 모델이 같이 틀린 걸 정답으로 굳히면 안 되니까.
"""
import hashlib
import json
import re

from sqlalchemy import select

from runs.models import RunFeedback, RunStep

FIELDS = ("store", "total", "date")

# 밖으로 보낼 때 가리는 숫자: 카드·승인·회원·전화번호 (상호·금액·날짜는 평가에 필요해서 그대로)
SENSITIVE_LINE = re.compile(r"카드\s*번호|승인\s*번호|회원\s*번호|가맹점\s*번호|TEL|전화|연락처|휴대폰", re.I)
PHONE = re.compile(r"(?<!\d)0\d{1,2}[-\s)]?\d{3,4}[-\s]?\d{4}(?!\d)")
CARD = re.compile(r"(?<!\d)\d{4}[-\s]?[\d*]{2,4}[-\s]?[\d*]{4}[-\s]?[\d*]{3,4}(?!\d)")


def _stars(m: re.Match) -> str:
    return re.sub(r"\d", "*", m.group())


def mask_text(text: str) -> str:
    """민감한 번호를 *로 가림. 해당 줄(카드번호·승인번호·TEL 등)은 줄 안 숫자 전부, 그 밖은 카드·전화 모양만"""
    lines = []
    for line in (text or "").split("\n"):
        if SENSITIVE_LINE.search(line):
            line = re.sub(r"\d", "*", line)
        else:
            line = PHONE.sub(_stars, CARD.sub(_stars, line))
        lines.append(line)
    return "\n".join(lines)


def step_output(db, run_id: int, node_type: str) -> dict:
    """같은 실행에서 해당 종류 노드가 낸 마지막 출력 (없으면 빈 dict)"""
    out = db.execute(
        select(RunStep.output)
        .where(RunStep.run_id == run_id, RunStep.node_type == node_type, RunStep.output.isnot(None))
        .order_by(RunStep.id.desc())
        .limit(1)
    ).scalar_one_or_none()
    return out or {}


def collect_golden(db, doc_type: str = "영수증", masked: bool = False) -> tuple[list[dict], list[dict], int]:
    """(평가셋 행, 상호 수정 사례, 텍스트가 없어 뺀 수). masked=True면 OCR 텍스트의 민감한 번호를 가림"""
    records, corrections, skipped = [], [], 0
    feedbacks = db.execute(
        select(RunFeedback)
        .where(RunFeedback.label_source == "human", RunFeedback.doc_type == doc_type)
        .order_by(RunFeedback.id)
    ).scalars().all()

    for fb in feedbacks:
        ocr = step_output(db, fb.run_id, "ocr_extract")
        # 영수증 여러 장 실행: 라벨에 장 정보(_receipt)가 있으면 그 장의 OCR 텍스트·신뢰도만
        receipt = (fb.final_output or {}).get("_receipt") or {}
        if receipt.get("file_id") is not None and len(ocr.get("pages") or []) > 1:
            page = next((p for p in ocr["pages"] if p.get("file_id") == receipt["file_id"]), None)
            ocr = {"text": page.get("text"), "confidence": page.get("confidence")} if page else {}
        if not ocr.get("text"):                # 입력 텍스트가 없으면 다시 돌려볼 수 없음
            skipped += 1
            continue
        label = {k: fb.final_output.get(k) for k in FIELDS}
        records.append({
            "id": f"g{fb.id:05d}",                 # 피드백 행마다 하나 → 여러 장 실행도 장마다 다른 id
            "split": "test",
            "text": mask_text(ocr["text"]) if masked else ocr["text"],
            "confidence": ocr.get("confidence"),
            "label": label,
            # 합성 데이터의 meta(사진 여부·함정 등)는 실제 데이터엔 없음 → 출처 정보만
            "meta": {"source": "human", "run_id": fb.run_id, "model_version": fb.model_version,
                     "edited_fields": fb.edited_fields or [], "traps": []},
        })
        # 사람이 상호명을 고쳤다 = OCR이 틀렸을 가능성 → 원본 파일과 함께 파인튜닝 후보로
        if "store" in (fb.edited_fields or []):
            files = step_output(db, fb.run_id, "file_upload").get("files", [])
            if receipt.get("file_id") is not None:     # 여러 장: 고친 그 장의 원본만
                files = [f for f in files if f.get("file_id") == receipt["file_id"]] or files
            corrections.append({
                "run_id": fb.run_id,
                "files": files,
                "model_store": fb.model_output.get("store"),
                "correct_store": label["store"],
            })
    return records, corrections, skipped


def to_jsonl(rows: list[dict]) -> str:
    return "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows)


def golden_sha(rows: list[dict]) -> str:
    """내용 해시 (데이터 버전). 같으면 이미 보낸 것 → 다시 안 보냄"""
    return hashlib.sha256(to_jsonl(rows).encode("utf-8")).hexdigest()[:12]