from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from core.auth import get_user_id
from core.database import get_db
from core.response import ApiResult, ok
from workflows import service
from workflows.schemas import (
    DeployResult,
    Graph,
    InputFormRequest,
    InputFormResponse,
    PlacementsRequest,
    ValidationResult,
    VersionInfo,
    WorkflowCreateRequest,
    WorkflowDetail,
    WorkflowSummary,
    WorkflowUpdateRequest,
)

router = APIRouter(prefix="/workflows", tags=["워크플로우"])


# 1. 생성
@router.post("", response_model=ApiResult[WorkflowDetail], status_code=201)
def create_workflow(
    req: WorkflowCreateRequest,
    db: Session = Depends(get_db),
    user_id: str | None = Depends(get_user_id),
):
    workflow = service.create_workflow(db, req, user_id)
    return ok(WorkflowDetail.model_validate(workflow))


# 2. 목록
@router.get("", response_model=ApiResult[list[WorkflowSummary]])
def list_workflows(q: str | None = None, db: Session = Depends(get_db)):
    workflows = service.list_workflows(db, q)
    return ok([WorkflowSummary.model_validate(w) for w in workflows])


# 10-1. 현재 화면에서 실행 가능한 워크플로우  ⚠️ /{workflow_id}보다 위에
@router.get("/available", response_model=ApiResult[list[WorkflowSummary]])
def list_available(screen: str, db: Session = Depends(get_db)):
    workflows = service.list_available(db, screen)
    return ok([WorkflowSummary.model_validate(w) for w in workflows])


# 3. 상세
@router.get("/{workflow_id}", response_model=ApiResult[WorkflowDetail])
def get_workflow(workflow_id: int, db: Session = Depends(get_db)):
    workflow = service.get_workflow(db, workflow_id)
    return ok(WorkflowDetail.model_validate(workflow))


# 5. 이름·설명 수정
@router.patch("/{workflow_id}", response_model=ApiResult[WorkflowDetail])
def update_workflow(workflow_id: int, req: WorkflowUpdateRequest, db: Session = Depends(get_db)):
    workflow = service.update_workflow(db, workflow_id, req)
    return ok(WorkflowDetail.model_validate(workflow))


# 6. 삭제
@router.delete("/{workflow_id}", response_model=ApiResult[None])
def delete_workflow(workflow_id: int, db: Session = Depends(get_db)):
    service.delete_workflow(db, workflow_id)
    return ok()


# 4. 그래프 저장
@router.put("/{workflow_id}/graph", response_model=ApiResult[WorkflowDetail])
def save_graph(workflow_id: int, graph: Graph, db: Session = Depends(get_db)):
    workflow = service.save_graph(db, workflow_id, graph)
    return ok(WorkflowDetail.model_validate(workflow))


# 7. 흐름 검사
@router.post("/{workflow_id}/validate", response_model=ApiResult[ValidationResult])
def validate_workflow(workflow_id: int, db: Session = Depends(get_db)):
    return ok(service.validate_workflow(db, workflow_id))


# 8. 배포
@router.post("/{workflow_id}/deploy", response_model=ApiResult[DeployResult])
def deploy_workflow(workflow_id: int, db: Session = Depends(get_db)):
    return ok(service.deploy_workflow(db, workflow_id))


# 9-1. 입력 폼 설정
@router.put("/{workflow_id}/input-form", response_model=ApiResult[WorkflowDetail])
def save_input_form(workflow_id: int, req: InputFormRequest, db: Session = Depends(get_db)):
    workflow = service.save_input_form(db, workflow_id, req)
    return ok(WorkflowDetail.model_validate(workflow))


# 10-2. 입력 폼 조회 (실행 직전)
@router.get("/{workflow_id}/input-form", response_model=ApiResult[InputFormResponse])
def get_input_form(
    workflow_id: int,
    screen: str | None = None,
    record_id: str | None = Query(default=None, alias="recordId"),
    db: Session = Depends(get_db),
):
    return ok(service.get_input_form(db, workflow_id, screen, record_id))


# 9-2. 노출 화면 설정
@router.put("/{workflow_id}/placements", response_model=ApiResult[WorkflowDetail])
def save_placements(workflow_id: int, req: PlacementsRequest, db: Session = Depends(get_db)):
    workflow = service.save_placements(db, workflow_id, req)
    return ok(WorkflowDetail.model_validate(workflow))


# 11-1. 복제
@router.post("/{workflow_id}/duplicate", response_model=ApiResult[WorkflowDetail], status_code=201)
def duplicate_workflow(
    workflow_id: int,
    db: Session = Depends(get_db),
    user_id: str | None = Depends(get_user_id),
):
    workflow = service.duplicate_workflow(db, workflow_id, user_id)
    return ok(WorkflowDetail.model_validate(workflow))


# 11-2. 버전 목록
@router.get("/{workflow_id}/versions", response_model=ApiResult[list[VersionInfo]])
def list_versions(workflow_id: int, db: Session = Depends(get_db)):
    return ok(service.list_versions(db, workflow_id))


# 11-3. 이전 버전으로 되돌리기
@router.post("/{workflow_id}/versions/{version}/activate", response_model=ApiResult[DeployResult])
def activate_version(workflow_id: int, version: int, db: Session = Depends(get_db)):
    return ok(service.activate_version(db, workflow_id, version))