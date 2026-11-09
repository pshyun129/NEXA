from datetime import datetime

from core.schemas import CamelModel


class RunCreateRequest(CamelModel):
    inputs: dict = {}
    file_ids: list[int] = []


class ResumeRequest(CamelModel):
    approved: bool                  # 확인(True) / 반려(False)
    comment: str | None = None      # 의견
    data: dict | None = None        # 사람이 고친 값 (human_confirm용, 고친 필드만 보내도 됨)


class RunCreated(CamelModel):
    run_id: int
    status: str


class RunSummary(CamelModel):
    id: int
    workflow_id: int
    version: int | None
    is_test: bool
    status: str
    current_node_id: str | None
    requested_by: str | None
    created_at: datetime
    started_at: datetime | None
    finished_at: datetime | None


class RunDetail(RunSummary):
    inputs: dict
    outputs: dict | None
    error: str | None


class RunStepResponse(CamelModel):
    id: int
    node_id: str
    node_type: str
    status: str
    branch: str | None
    output: dict | None
    error: str | None
    started_at: datetime
    finished_at: datetime | None


# 파일 응답
class RunFileResponse(CamelModel):
    id: int
    run_id: int | None
    original_name: str
    content_type: str | None
    size: int
    uploaded_by: str | None
    created_at: datetime

