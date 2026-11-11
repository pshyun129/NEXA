"""결재 결과 보관함(inbox)과 결재를 기다리는 실행 재개.

이벤트가 대기 노드보다 먼저 오든 나중에 오든, 같은 이벤트가 두 번 오든 결과가 한 번만 반영되게 한다.
  1) 이벤트를 받으면 먼저 보관함에 저장 (requestKey당 1건, 이미 있으면 그대로 둠)
  2) 그 requestKey를 기다리는 실행이 있으면 재개 (WAITING → RUNNING 전환에 성공한 쪽만 진행)
"""
import logging

from sqlalchemy import delete, or_, select
from sqlalchemy.dialects.postgresql import insert

from core.database import SessionLocal
from runs.models import ApprovalResult, RunStatus, RunStep

log = logging.getLogger(__name__)


def save_result(event: dict) -> None:
    """결과 저장. 이미 있으면 아무것도 안 함 (중복 이벤트)"""
    with SessionLocal() as db:
        db.execute(
            insert(ApprovalResult)
            .values(
                request_key=event["requestKey"],
                status=event["status"],
                document_id=event.get("documentId"),
                doc_no=event.get("docNo"),
                event_id=event.get("eventId"),
            )
            .on_conflict_do_nothing(index_elements=["request_key"])
        )
        db.commit()


def get_result(request_key: str) -> dict | None:
    """보관함에서 결과 조회. 없으면 None"""
    with SessionLocal() as db:
        r = db.get(ApprovalResult, request_key)
        if r is None:
            return None
        return {"status": r.status, "document_id": r.document_id, "doc_no": r.doc_no, "request_key": r.request_key}


def _find_waiting_run(request_key: str) -> int | None:
    """이 requestKey의 결재를 기다리며 멈춰 있는 실행 (대기 단계 출력의 waiting_for에 키가 있음)
    문서 여러 건을 기다리면 waiting_for.request_keys 목록에 들어 있음"""
    waiting = RunStep.output["waiting_for"]
    with SessionLocal() as db:
        step = db.scalars(
            select(RunStep).where(
                RunStep.status == RunStatus.WAITING,
                or_(waiting["request_key"].astext == request_key,
                    waiting["request_keys"].contains([request_key])),
            )
        ).first()
        return step.run_id if step else None


def resume_if_ready(request_key: str) -> bool:
    """결과가 보관함에 있고, 그걸 기다리는 실행이 있으면 재개. 재개를 시도했으면 True"""
    result = get_result(request_key)
    if result is None:
        return False
    run_id = _find_waiting_run(request_key)
    if run_id is None:
        return False

    from engine.runner import resume_run   # 순환 import 방지 (runner도 이 모듈을 씀)
    log.info("결재 결과로 실행 재개: run=%s status=%s", run_id, result["status"])
    resume_run(run_id, result)              # 이벤트로 재개 → 사용자 토큰 없음
    return True


def delete_results_for_run(run_id: int) -> None:
    """끝난 실행의 결재 결과를 보관함에서 정리.

    대기 노드를 지나 실행이 끝났으니 다시 읽을 일이 없다.
    노드 안에서 지우면 체크포인트 저장 전에 서버가 죽었을 때 결과를 잃으므로, 실행 종료 시점에 지운다.
    """
    with SessionLocal() as db:
        steps = db.scalars(select(RunStep).where(RunStep.run_id == run_id)).all()
        keys = []
        for s in steps:
            if not isinstance(s.output, dict):
                continue
            keys += s.output.get("request_keys") or []           # 문서 여러 건
            if s.output.get("request_key"):
                keys.append(s.output["request_key"])
        keys = list(dict.fromkeys(keys))
        if keys:
            db.execute(delete(ApprovalResult).where(ApprovalResult.request_key.in_(keys)))
            db.commit()


