import copy
from datetime import timedelta

from fastapi import UploadFile
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from core.exceptions import BusinessException, ErrorCode
from runs import files
from runs.models import FINISHED_STATUSES, Run, RunFile, RunStatus, RunStep
from runs.schemas import RunCreateRequest
from workflows.models import Workflow
from workflows.validator import validate_graph


def _get(db: Session, run_id: int) -> Run:
    run = db.get(Run, run_id)
    if run is None:
        raise BusinessException(ErrorCode.RUN_NOT_FOUND)
    return run


def _get_workflow(db: Session, workflow_id: int) -> Workflow:
    workflow = db.get(Workflow, workflow_id)
    if workflow is None:
        raise BusinessException(ErrorCode.WF_NOT_FOUND)
    return workflow


def _get_file(db: Session, file_id: int) -> RunFile:
    run_file = db.get(RunFile, file_id)
    if run_file is None:
        raise BusinessException(ErrorCode.FILE_NOT_FOUND)
    return run_file


def _take_files(db: Session, file_ids: list[int]) -> list[RunFile]:
    run_files = []
    for file_id in file_ids:
        run_file = _get_file(db, file_id)
        if run_file.run_id is not None:
            raise BusinessException(ErrorCode.FILE_ALREADY_USED)
        run_files.append(run_file)
    return run_files


def _file_info(run_file: RunFile) -> dict:
    return {
        "file_id": run_file.id,
        "name": run_file.original_name,
        "content_type": run_file.content_type,
        "size": run_file.size,
        "path": str(files.file_path(run_file.stored_name)),
    }


def _check_required_inputs(workflow: Workflow, inputs: dict, file_ids: list[int]) -> None:
    for field in workflow.input_form:
        if field["type"] == "file":
            if field.get("required") and not file_ids:
                raise BusinessException(ErrorCode.RUN_MISSING_INPUT, f"'{field['label']}' 파일이 첨부되지 않았습니다.")
            continue
        if field.get("required") and not inputs.get(field["key"]):
            raise BusinessException(ErrorCode.RUN_MISSING_INPUT, f"'{field['label']}' 값이 비어 있습니다.")


def create_run(db: Session, workflow_id: int, req: RunCreateRequest, user_id: str | None) -> Run:
    workflow = _get_workflow(db, workflow_id)
    if workflow.deployed_version is None:
        raise BusinessException(ErrorCode.WF_NOT_DEPLOYED)

    version = next(v for v in workflow.versions if v.version == workflow.deployed_version)
    _check_required_inputs(workflow, req.inputs, req.file_ids)
    run_files = _take_files(db, req.file_ids)

    run = Run(
        workflow_id=workflow.id,
        version=version.version,
        graph=copy.deepcopy(version.graph),
        inputs={**req.inputs, "files": [_file_info(f) for f in run_files]},
        requested_by=user_id,
    )
    db.add(run)
    db.flush()
    for run_file in run_files:
        run_file.run_id = run.id
    db.commit()
    db.refresh(run)
    return run


def create_test_run(db: Session, workflow_id: int, req: RunCreateRequest, user_id: str | None) -> Run:
    workflow = _get_workflow(db, workflow_id)

    issues = validate_graph(workflow.draft_graph)
    if issues:
        raise BusinessException(ErrorCode.WF_INVALID_GRAPH, issues[0]["message"])

    run_files = _take_files(db, req.file_ids)
    run = Run(
        workflow_id=workflow.id,
        version=None,
        is_test=True,
        graph=copy.deepcopy(workflow.draft_graph),
        inputs={**req.inputs, "files": [_file_info(f) for f in run_files]},
        requested_by=user_id,
    )
    db.add(run)
    db.flush()
    for run_file in run_files:
        run_file.run_id = run.id
    db.commit()
    db.refresh(run)
    return run


def check_run_access(run: Run, user_id: str, is_admin: bool = False) -> Run:
    """실행을 보거나 취소할 수 있는 사람: 요청자 본인, 또는 관리자(운영 확인용)
    (재개는 따로: 재개한 사람의 토큰으로 결재가 상신되므로 관리자도 불가 → routers.resume)"""
    if run.requested_by != user_id and not is_admin:
        raise BusinessException(ErrorCode.RUN_FORBIDDEN)
    return run


def get_run(db: Session, run_id: int, user_id: str, is_admin: bool = False) -> Run:
    return check_run_access(_get(db, run_id), user_id, is_admin)


def list_steps(db: Session, run_id: int, user_id: str, is_admin: bool = False) -> list[RunStep]:
    return get_run(db, run_id, user_id, is_admin).steps


def list_my_runs(db: Session, user_id: str | None, status: str | None) -> list[Run]:
    stmt = select(Run).where(Run.requested_by == user_id).order_by(Run.id.desc()).limit(50)
    if status:
        stmt = stmt.where(Run.status == status)
    return list(db.scalars(stmt).all())


def cancel_run(db: Session, run_id: int, user_id: str, is_admin: bool = False) -> Run:
    run = get_run(db, run_id, user_id, is_admin)
    if run.status in FINISHED_STATUSES:
        raise BusinessException(ErrorCode.RUN_ALREADY_FINISHED)
    run.status = RunStatus.CANCELED
    run.finished_at = func.now()
    db.commit()
    db.refresh(run)
    return run


# 재개 가능한지 확인
def get_waiting_run(db: Session, run_id: int) -> Run:
    run = _get(db, run_id)
    if run.status != RunStatus.WAITING:
        raise BusinessException(ErrorCode.RUN_NOT_WAITING)
    return run


def get_waiting_payload(db: Session, run_id: int) -> dict:
    """멈춘 단계가 무엇을 기다리는지 (waiting_for). 없으면 빈 dict"""
    step = db.scalars(
        select(RunStep).where(RunStep.run_id == run_id, RunStep.status == RunStatus.WAITING)
    ).first()
    return ((step.output or {}).get("waiting_for") or {}) if step else {}


# 내 확인 대기 목록: 내가 처리할 사람 확인만. 결재 대기는 결재자가 처리하므로 빼고 결재함이 맡는다 (QA C34)
def list_my_pending(db: Session, user_id: str | None) -> list[Run]:
    stmt = (
        select(Run)
        .where(Run.requested_by == user_id)
        .where(Run.status == RunStatus.WAITING)
        .order_by(Run.id.desc())
    )
    return [run for run in db.scalars(stmt).all() if run.waiting_type != "approval_wait"]


# 첨부 파일 업로드
def upload_file(db: Session, file: UploadFile, user_id: str | None) -> RunFile:
    cleanup_unused_files(db)
    stored_name, size = files.save_upload(file)
    run_file = RunFile(
        original_name=file.filename or "unknown",
        stored_name=stored_name,
        content_type=file.content_type,
        size=size,
        uploaded_by=user_id,
    )
    db.add(run_file)
    db.commit()
    db.refresh(run_file)
    return run_file


def _check_file_access(db: Session, run_file: RunFile, user_id: str) -> None:
    """파일을 볼 수 있는 사람: 올린 사람, 또는 그 파일이 쓰인 실행의 요청자 (엑셀 결과 파일은 ai-service가 올림)"""
    if run_file.uploaded_by == user_id:
        return
    if run_file.run_id is not None:
        run = db.get(Run, run_file.run_id)
        if run is not None and run.requested_by == user_id:
            return
    raise BusinessException(ErrorCode.RUN_FORBIDDEN)


def get_file(db: Session, file_id: int, user_id: str) -> RunFile:
    run_file = _get_file(db, file_id)
    _check_file_access(db, run_file, user_id)
    return run_file


# 파일 첨부 취소: 올린 사람만 (실행에 쓰이기 전 파일만)
def delete_file(db: Session, file_id: int, user_id: str) -> None:
    run_file = _get_file(db, file_id)
    if run_file.uploaded_by != user_id:
        raise BusinessException(ErrorCode.RUN_FORBIDDEN)
    if run_file.run_id is not None:
        raise BusinessException(ErrorCode.FILE_ALREADY_USED)
    files.delete_stored(run_file.stored_name)
    db.delete(run_file)
    db.commit()


# 24시간 동안 실행에 안 쓰인 파일 정리
def cleanup_unused_files(db: Session) -> None:
    stmt = (
        select(RunFile)
        .where(RunFile.run_id.is_(None))
        .where(RunFile.created_at < func.now() - timedelta(hours=24))
    )
    for run_file in db.scalars(stmt).all():
        files.delete_stored(run_file.stored_name)
        db.delete(run_file)
    db.commit()


# 실행 결과 파일 다운로드: 올린 사람 본인 또는 그 파일이 쓰인 실행의 요청자만
def download_file(db: Session, file_id: int, user_id: str):
    run_file = get_file(db, file_id, user_id)
    path = files.file_path(run_file.stored_name)
    if not path.exists():
        raise BusinessException(ErrorCode.FILE_NOT_FOUND)
    return run_file, path


INTERRUPTED = "서버 재시작(배포)으로 중단된 실행입니다. 다시 실행해 주세요."


def fail_interrupted_runs(db: Session) -> int:
    """서버가 켜질 때: 실행 중(QUEUED·RUNNING)으로 남은 실행 = 이전 프로세스에서 끊긴 것 → 실패 처리.
    전제: ai-service 컨테이너 1개 (여러 개로 늘리면 다른 인스턴스의 실행까지 실패시키므로 이 방식은 못 씀).
    WAITING은 사람 확인·Kafka 결과로 재개되므로 그대로 둠."""
    runs = db.scalars(select(Run).where(Run.status.in_([RunStatus.QUEUED, RunStatus.RUNNING]))).all()
    for run in runs:
        run.status = RunStatus.FAILED
        run.error = INTERRUPTED
        run.finished_at = func.now()
        for step in run.steps:
            if step.status == RunStatus.RUNNING:
                step.status = RunStatus.FAILED
                step.error = INTERRUPTED
                step.finished_at = func.now()
    db.commit()
    return len(runs)