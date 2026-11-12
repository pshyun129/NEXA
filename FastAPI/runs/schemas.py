from datetime import datetime

from core.schemas import CamelModel


class RunCreateRequest(CamelModel):
    inputs: dict = {}
    file_ids: list[int] = []


class ResumeRequest(CamelModel):
    approved: bool                  # 확인(True) / 반려(False)
    comment: str | None = None      # 의견
    data: dict | None = None        # 사람이 고친 값 (human_confirm용, 고친 필드만 보내도 됨)
    # 영수증 여러 장: 장마다 [{index, data(고친 값), facts(사용 목적·참석 인원 …), include, note}]
    receipts: list[dict] | None = None


class PolicyPreviewRequest(CamelModel):
    """사람 확인 화면의 [다시 검토]: 고친 값·사실로 규정 검토만 미리 돌려 봄 (실행은 그대로 대기)"""
    receipts: list[dict]            # [{index, data, facts}]


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
    # WAITING일 때 무엇을 기다리는지(human_confirm / approval_wait). 화면이 "확인 대기"와 "결재 대기"를 구분한다
    waiting_type: str | None = None


class RunDetail(RunSummary):
    inputs: dict
    outputs: dict | None
    error: str | None
    # 실행 당시 그래프(배포 버전 또는 테스트 실행 초안의 사본). 화면이 이 그래프로 진행 캔버스를 그린다
    # → 실행 뒤 초안을 고쳐도 예전 실행은 그때 모양 그대로 보인다 (조회는 요청자·관리자만)
    graph: dict


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

