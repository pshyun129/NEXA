from fastapi import APIRouter, BackgroundTasks, Depends, File, UploadFile
from fastapi.responses import FileResponse
from sqlalchemy.orm import Session

from core.auth import AuthUser, get_current_user, get_user_id
from core.database import get_db
from core.response import ApiResult, ok
from core.exceptions import BusinessException, ErrorCode
from engine.runner import execute_run, resume_run
from runs import service
from runs.schemas import (
    ResumeRequest, 
    RunCreated, 
    RunCreateRequest, 
    RunDetail,
    RunFileResponse, 
    RunStepResponse, 
    RunSummary,
)

router = APIRouter(tags=["실행"])


@router.post("/workflows/{workflow_id}/runs", response_model=ApiResult[RunCreated], status_code=202)
def create_run(
    workflow_id: int,
    req: RunCreateRequest,
    background_tasks: BackgroundTasks,
    db: Session = Depends(get_db),
    user: AuthUser = Depends(get_current_user),
):
    run = service.create_run(db, workflow_id, req, str(user.employee_id))
    background_tasks.add_task(execute_run, run.id, user.token)
    return ok({"run_id": run.id, "status": run.status})


@router.post("/workflows/{workflow_id}/test-runs", response_model=ApiResult[RunCreated], status_code=202)
def create_test_run(
    workflow_id: int,
    req: RunCreateRequest,
    background_tasks: BackgroundTasks,
    db: Session = Depends(get_db),
    user: AuthUser = Depends(get_current_user),
):
    run = service.create_test_run(db, workflow_id, req, str(user.employee_id))
    background_tasks.add_task(execute_run, run.id, user.token)
    return ok({"run_id": run.id, "status": run.status})


@router.post("/runs/files", response_model=ApiResult[RunFileResponse], status_code=201)
def upload_file(
    file: UploadFile = File(...),
    db: Session = Depends(get_db),
    user_id: str | None = Depends(get_user_id),
):
    run_file = service.upload_file(db, file, user_id)
    return ok(RunFileResponse.model_validate(run_file))


@router.get("/runs/files/{file_id}", response_model=ApiResult[RunFileResponse])
def get_file(file_id: int, db: Session = Depends(get_db), user_id: str = Depends(get_user_id)):
    run_file = service.get_file(db, file_id, user_id)
    return ok(RunFileResponse.model_validate(run_file))


@router.get("/runs/files/{file_id}/download")
def download_file(file_id: int, db: Session = Depends(get_db), user_id: str = Depends(get_user_id)):
    run_file, path = service.download_file(db, file_id, user_id)
    # 한글 파일 이름도 브라우저에서 그대로 저장됨 (Content-Disposition filename*=UTF-8)
    return FileResponse(path, filename=run_file.original_name,
                        media_type=run_file.content_type or "application/octet-stream")


@router.delete("/runs/files/{file_id}", response_model=ApiResult[None])
def delete_file(file_id: int, db: Session = Depends(get_db), user_id: str = Depends(get_user_id)):
    service.delete_file(db, file_id, user_id)
    return ok()


@router.get("/runs/me", response_model=ApiResult[list[RunSummary]])
def list_my_run(
    status: str | None = None,
    db: Session = Depends(get_db),
    user_id: str | None = Depends(get_user_id),
):
    runs = service.list_my_runs(db, user_id, status)
    return ok([RunSummary.model_validate(r) for r in runs])


@router.get("/runs/me/pending", response_model=ApiResult[list[RunSummary]])
def list_my_pending(
    db: Session = Depends(get_db),
    user_id: str | None = Depends(get_user_id), 
):
    runs = service.list_my_pending(db, user_id)
    return ok([RunSummary.model_validate(r) for r in runs])


# 실행 조회·취소: 요청자 본인 또는 관리자만 (2026-11-06 실서버 E2E에서 다른 사원도 조회되는 것 발견)
@router.get("/runs/{run_id}", response_model=ApiResult[RunDetail])
def get_run(run_id: int, db: Session = Depends(get_db), user: AuthUser = Depends(get_current_user)):
    run = service.get_run(db, run_id, str(user.employee_id), user.is_admin)
    return ok(RunDetail.model_validate(run))


@router.get("/runs/{run_id}/steps", response_model=ApiResult[list[RunStepResponse]])
def list_steps(run_id: int, db: Session = Depends(get_db), user: AuthUser = Depends(get_current_user)):
    steps = service.list_steps(db, run_id, str(user.employee_id), user.is_admin)
    return ok([RunStepResponse.model_validate(s) for s in steps])


@router.post("/runs/{run_id}/cancel", response_model=ApiResult[RunDetail])
def cancel_run(run_id: int, db: Session = Depends(get_db), user: AuthUser = Depends(get_current_user)):
    run = service.cancel_run(db, run_id, str(user.employee_id), user.is_admin)
    return ok(RunDetail.model_validate(run))


@router.post("/runs/{run_id}/resume", response_model=ApiResult[RunCreated], status_code=202)
def resume(
    run_id: int,
    req: ResumeRequest,
    background_tasks: BackgroundTasks,
    db: Session = Depends(get_db),
    user: AuthUser = Depends(get_current_user),
):
    run = service.get_waiting_run(db, run_id)
    # 재개한 사람의 토큰으로 결재가 상신되므로 요청자 본인만 재개 가능 (관리자도 불가: 기안자가 바뀜)
    if run.requested_by != str(user.employee_id):
        raise BusinessException(ErrorCode.RUN_FORBIDDEN)
    # 결재 대기는 Kafka 결과로만 재개 (사용자가 직접 재개하면 결재를 건너뛰게 됨)
    if service.get_waiting_payload(db, run.id).get("type") == "approval_wait":
        raise BusinessException(ErrorCode.RUN_WAITING_APPROVAL)
    background_tasks.add_task(resume_run, run.id, req.model_dump(), user.token)
    return ok({"run_id": run.id, "status": run.status})