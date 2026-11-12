"""다른 실행에서 이미 정산을 신청한 영수증 찾기 (이중 청구 방지).

영수증 여러 장을 한 번에 올리다 보면 지난번에 낸 영수증이 다시 섞여 들어가기 쉽다.
같은 실행 안의 중복은 structured_extract가 보고, 여기서는 다른 실행(테스트 실행 제외, 모든 사원)을 본다.

빠르게 찾기: 항목 추출 때 장마다 키(거래일·금액·승인번호·사업자번호·결제 시각·상호)를 receipt_key 표에 한 줄씩 저장하고,
            (거래일, 금액) 인덱스로 후보만 꺼낸다 → 데이터가 쌓여도 검사 시간이 거의 그대로
            (처음엔 단계 출력 JSONB를 다 펼쳐 찾았는데, 기록이 쌓이면 실행마다 수십~수백 MB를 읽게 됨 — 2026-11-12 리뷰)

같은 영수증인지:
  - 둘 다 카드 승인번호가 있으면 → 승인번호와 금액이 같아야 같은 영수증 (OCR 상호 오타와 상관없이 가장 확실)
  - 아니면 → 거래일·금액·결제 시각이 같고, 사업자번호나 상호(띄어쓰기 무시)가 같으면 같은 영수증

이미 '청구된' 것으로 보는 경우 (후보가 나왔을 때만 그 실행의 단계 출력에서 확인):
  승인된 결재 문서에 들어감 → 청구됨 / 결재 대기 중 → 결재 중 / 확인·검토 중인 실행 → 다른 실행에서 처리 중
  반려·회수된 문서, 정산에서 뺀 장, 결재 전에 끝난 실행(위반으로 종료 등), 실패·취소된 실행 → 청구 아님 (다시 내도 됨)
"""
import logging
import re
from datetime import datetime, timedelta, timezone

from sqlalchemy import delete, func, select, text
from sqlalchemy.orm import Session

from runs.models import ReceiptKey, Run, RunStatus

log = logging.getLogger(__name__)
LOOKBACK_DAYS = 400
# 장별 결과가 있는 단계를 늦은 것부터 (그 실행에서 이 장이 마지막으로 어떻게 됐는지)
LATER_STEPS = ["wait_approval", "approval_draft", "human_confirm", "policy_check", "llm_verify", "structured_extract"]

_LATER = text("""
    SELECT node_type, output FROM run_step
     WHERE run_id = :run_id AND status = 'SUCCEEDED' AND node_type = ANY(:types) AND output ? 'receipts'
     ORDER BY id DESC
""")


def _v(r: dict, name: str):
    f = (r.get("details") or {}).get(name)
    return f.get("value") if isinstance(f, dict) else None


def _compact(s) -> str:
    return re.sub(r"\s+", "", str(s or ""))


def key_of(r: dict) -> dict | None:
    """장 → 비교용 키 (거래일·금액이 없으면 비교할 수 없어 None)"""
    f = r.get("fields") or {}
    try:
        total = int(f.get("total"))
    except (TypeError, ValueError):
        return None
    if not f.get("date") or not total:
        return None
    return {"date": str(f["date"])[:10], "total": total, "approval_no": _v(r, "approval_no"),
            "biz_no": _v(r, "biz_no"), "pay_time": _v(r, "pay_time"), "store": _compact(f.get("store"))[:100] or None}


def same_key(a: dict, b: dict) -> bool:
    if a["total"] != b["total"] or a["date"] != b["date"]:
        return False
    if a["approval_no"] and b["approval_no"]:
        return a["approval_no"] == b["approval_no"]
    if a["pay_time"] != b["pay_time"]:
        return False
    return bool(a["biz_no"] and a["biz_no"] == b["biz_no"]) or bool(a["store"] and a["store"] == b["store"])


def same_receipt(a: dict, b: dict) -> bool:
    ka, kb = key_of(a), key_of(b)
    return bool(ka and kb and same_key(ka, kb))


def save_keys(db: Session, run_id: int, receipts: list[dict]) -> None:
    """이 실행의 장 키 저장 (노드가 다시 실행돼도 겹치지 않게 지우고 다시)"""
    db.execute(delete(ReceiptKey).where(ReceiptKey.run_id == run_id))
    for r in receipts:
        k = key_of(r)
        if k:
            db.add(ReceiptKey(run_id=run_id, receipt_index=r.get("index", 0), **k))


def _fate(db: Session, run_id: int, run_status: str, index: int) -> dict | None:
    """그 실행에서 이 장(장 번호)의 최종 상태. 청구로 볼 수 없으면 None"""
    rows = db.execute(_LATER, {"run_id": run_id, "types": LATER_STEPS}).all()
    by_type: dict[str, dict] = {}
    for node_type, output in rows:
        by_type.setdefault(node_type, output)               # 단계마다 마지막 출력
    for node_type in LATER_STEPS:
        out = by_type.get(node_type)
        if not out:
            continue
        # 같은 실행 안에서는 장 번호(index)가 단계마다 그대로 → 번호로 찾음
        # (내용으로 찾으면 그 실행의 사람 확인에서 금액을 고친 장은 못 찾아 '청구 아님'으로 잘못 봄)
        mine = next((x for x in out.get("receipts") or [] if x.get("index") == index), None)
        if mine is None:
            continue
        if not mine.get("include", True):
            return None                                       # 정산에서 뺀 장
        approval = mine.get("approval") or {}
        if node_type == "wait_approval":
            status = approval.get("status")
            if status in ("REJECTED", "CANCEL"):
                return None
            return {"state": "청구됨" if status == "APPROVED" else "결재 중", "doc_no": approval.get("doc_no"),
                    "approval_status": status}
        if node_type == "approval_draft":
            return {"state": "결재 중", "doc_no": approval.get("doc_no"), "approval_status": "INPROGRESS"}
        # 결재 전 단계까지만 있음: 실행이 아직 진행 중이면 진행 중, 끝났으면 결재 없이 끝난 것 (청구 아님)
        if run_status in (RunStatus.RUNNING, RunStatus.WAITING, RunStatus.QUEUED):
            return {"state": "다른 실행에서 처리 중", "doc_no": None, "approval_status": None}
        return None
    return None


def find_prior_claims(db: Session, run_id: int, receipts: list[dict]) -> dict[int, dict]:
    """장 번호 → 지난 청구 정보 {run_id, state, doc_no, requested_by, created_at}. 없으면 빈 dict"""
    keys = {r.get("index", 0): k for r in receipts if (k := key_of(r))}
    if not keys:
        return {}
    since = datetime.now(timezone.utc) - timedelta(days=LOOKBACK_DAYS)
    rows = db.execute(
        select(ReceiptKey, Run.status, Run.requested_by, Run.created_at)
        .join(Run, Run.id == ReceiptKey.run_id)
        .where(ReceiptKey.date.in_({k["date"] for k in keys.values()}),
               ReceiptKey.total.in_({k["total"] for k in keys.values()}),
               ReceiptKey.run_id != run_id,
               Run.is_test.is_(False),
               Run.status.notin_([RunStatus.FAILED, RunStatus.CANCELED]),
               Run.created_at > since.replace(tzinfo=None))
    ).all()
    found: dict[int, dict] = {}
    fates: dict[tuple, dict | None] = {}
    for idx, k in keys.items():
        for rk, run_status, requested_by, created_at in rows:
            cand = {c: getattr(rk, c) for c in ("date", "total", "approval_no", "biz_no", "pay_time", "store")}
            if not same_key(k, cand):
                continue
            fk = (rk.run_id, rk.receipt_index)
            if fk not in fates:
                fates[fk] = _fate(db, rk.run_id, run_status, rk.receipt_index)
            if fates[fk]:
                found[idx] = {"run_id": rk.run_id, "requested_by": requested_by,
                              "created_at": created_at.isoformat() if created_at else None, **fates[fk]}
                break
    return found


def backfill_keys(db: Session) -> int:
    """표가 비어 있으면(처음 배포) 지난 실행의 항목 추출 결과에서 키를 한 번 채움. 채운 줄 수"""
    if db.scalar(select(func.count()).select_from(ReceiptKey)):
        return 0
    rows = db.execute(text("""
        SELECT run_id, output -> 'receipts' FROM run_step
         WHERE node_type = 'structured_extract' AND status = 'SUCCEEDED' AND output ? 'receipts'
    """)).all()
    for run_id, receipts in rows:
        save_keys(db, run_id, receipts or [])
    db.commit()
    return db.scalar(select(func.count()).select_from(ReceiptKey)) or 0
