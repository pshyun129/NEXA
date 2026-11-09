import copy

from sqlalchemy import select
from sqlalchemy.orm import Session

from core.exceptions import BusinessException, ErrorCode
from runs.models import Run
from workflows.models import Workflow, WorkflowVersion
from workflows.schemas import (
    Graph,
    InputFormRequest,
    PlacementsRequest,
    WorkflowCreateRequest,
    WorkflowUpdateRequest,
)
from workflows.validator import validate_graph


def _get(db: Session, workflow_id: int) -> Workflow:
    workflow = db.get(Workflow, workflow_id)
    if workflow is None:
        raise BusinessException(ErrorCode.WF_NOT_FOUND)
    return workflow


# 1. 생성
def create_workflow(db: Session, req: WorkflowCreateRequest, user_id: str | None) -> Workflow:
    workflow = Workflow(name=req.name, description=req.description, created_by=user_id)
    db.add(workflow)
    db.commit()
    db.refresh(workflow)
    return workflow


# 2. 목록
def list_workflows(db: Session, q: str | None) -> list[Workflow]:
    stmt = select(Workflow).order_by(Workflow.updated_at.desc())
    if q:
        stmt = stmt.where(Workflow.name.ilike(f"%{q}%"))
    return list(db.scalars(stmt).all())


# 3. 상세
def get_workflow(db: Session, workflow_id: int) -> Workflow:
    return _get(db, workflow_id)


# 4. 그래프 저장
def save_graph(db: Session, workflow_id: int, graph: Graph) -> Workflow:
    workflow = _get(db, workflow_id)
    workflow.draft_graph = graph.model_dump()
    db.commit()
    db.refresh(workflow)
    return workflow


# 5. 이름·설명 수정
def update_workflow(db: Session, workflow_id: int, req: WorkflowUpdateRequest) -> Workflow:
    workflow = _get(db, workflow_id)
    for key, value in req.model_dump(exclude_unset=True).items():
        setattr(workflow, key, value)
    db.commit()
    db.refresh(workflow)
    return workflow


# 6. 삭제
def delete_workflow(db: Session, workflow_id: int) -> None:
    workflow = _get(db, workflow_id)
    has_runs = db.scalars(select(Run.id).where(Run.workflow_id == workflow_id).limit(1)).first()
    if has_runs:
        raise BusinessException(ErrorCode.WF_HAS_RUNS)
    db.delete(workflow)
    db.commit()


# 7. 흐름 검사
def validate_workflow(db: Session, workflow_id: int) -> dict:
    workflow = _get(db, workflow_id)
    issues = validate_graph(workflow.draft_graph)
    return {"valid": not issues, "issues": issues}


# 8. 배포
def deploy_workflow(db: Session, workflow_id: int) -> dict:
    workflow = _get(db, workflow_id)

    issues = validate_graph(workflow.draft_graph)
    if issues:
        raise BusinessException(ErrorCode.WF_INVALID_GRAPH, issues[0]["message"])

    next_version = max((v.version for v in workflow.versions), default=0) + 1
    workflow.versions.append(
        WorkflowVersion(version=next_version, graph=copy.deepcopy(workflow.draft_graph))
    )
    workflow.deployed_version = next_version
    db.commit()
    return {"version": next_version}


# 9-1. 입력 폼 설정
def save_input_form(db: Session, workflow_id: int, req: InputFormRequest) -> Workflow:
    workflow = _get(db, workflow_id)
    workflow.input_form = [f.model_dump() for f in req.fields]
    db.commit()
    db.refresh(workflow)
    return workflow


# 9-2. 노출 화면 설정
def save_placements(db: Session, workflow_id: int, req: PlacementsRequest) -> Workflow:
    workflow = _get(db, workflow_id)
    workflow.placements = sorted(set(req.screens))
    db.commit()
    db.refresh(workflow)
    return workflow


# 10-1. 현재 화면에서 실행 가능한 워크플로우
def list_available(db: Session, screen: str) -> list[Workflow]:
    stmt = (
        select(Workflow)
        .where(Workflow.deployed_version.is_not(None))
        .where(Workflow.placements.contains([screen]))
        .order_by(Workflow.name)
    )
    return list(db.scalars(stmt).all())


# 10-2. 입력 폼 조회 (실행 직전)
def get_input_form(db: Session, workflow_id: int, screen: str | None, record_id: str | None) -> dict:
    workflow = _get(db, workflow_id)
    if workflow.deployed_version is None:
        raise BusinessException(ErrorCode.WF_NOT_DEPLOYED)

    # TODO: screen + record_id로 ERP 데이터를 조회해서 prefill 항목 채우기 (clients/에서 구현)
    values: dict = {}

    return {"workflow_id": workflow.id, "fields": workflow.input_form, "values": values}


# 11-1. 복제
def duplicate_workflow(db: Session, workflow_id: int, user_id: str | None) -> Workflow:
    src = _get(db, workflow_id)
    copied = Workflow(
        name=f"{src.name} (복사본)"[:100],
        description=src.description,
        draft_graph=copy.deepcopy(src.draft_graph),
        input_form=copy.deepcopy(src.input_form),
        created_by=user_id,
    )
    db.add(copied)
    db.commit()
    db.refresh(copied)
    return copied


# 11-2. 버전 목록
def list_versions(db: Session, workflow_id: int) -> list[dict]:
    workflow = _get(db, workflow_id)
    return [
        {"version": v.version, "created_at": v.created_at, "active": v.version == workflow.deployed_version}
        for v in reversed(workflow.versions)
    ]


# 11-3. 이전 버전으로 되돌리기
def activate_version(db: Session, workflow_id: int, version: int) -> dict:
    workflow = _get(db, workflow_id)
    if not any(v.version == version for v in workflow.versions):
        raise BusinessException(ErrorCode.WF_VERSION_NOT_FOUND)
    workflow.deployed_version = version
    db.commit()
    return {"version": version}