from datetime import datetime
from typing import Literal

from pydantic import Field

from core.schemas import CamelModel

# 그래프 (편집기 캔버스)
class Position(CamelModel):
    x: float
    y: float


class GraphNode(CamelModel):
    id: str  # 캔버스 안에서의 고유 id (예 : "n1")
    type: str  # 노드 종류 (예: "ocr_extract")
    position: Position  # 캔버스 좌표
    config: dict = {}  # 설정 칸에 채운 값


class GraphEdge(CamelModel):
    id: str
    source: str  # 출발 노드 id
    target: str  # 도착 노드 id
    branch: str | None = None  # 분기 노드에서 나가는 선이면 "적합" 같은 분기 이름


class Graph(CamelModel):
    nodes: list[GraphNode] = []
    edges: list[GraphEdge] = []


# 입력 폼
class FormField(CamelModel):
    key: str
    label: str
    type: Literal["text", "number", "date", "select", "file"]
    required: bool = False
    options: list[str] = []
    prefill: str | None = None  # 화면 데이터에서 자동으로 채울 항목 (예: "employee.name")


class InputFormRequest(CamelModel):
    fields: list[FormField]


class InputFormResponse(CamelModel):
    workflow_id: int
    fields: list[FormField]
    values: dict = {}  # 자동으로 채워진 값


# 노출 화면
class PlacementsRequest(CamelModel):
    screens: list[str]  # ERP 화면 코드 목록 (예: ["EXPENSE_LIST"])


# 워크 플로우
class WorkflowCreateRequest(CamelModel):
    name: str = Field(min_length=1, max_length=100)
    description: str | None = None


class WorkflowUpdateRequest(CamelModel):
    name: str | None = Field(default=None, min_length=1, max_length=100)
    description: str | None = None


class WorkflowSummary(CamelModel):
    id: int
    name: str
    description: str | None
    deployed_version: int | None
    created_by: str | None
    updated_at: datetime


class WorkflowDetail(WorkflowSummary):
    draft_graph: Graph
    input_form: list[FormField]
    placements: list[str]
    created_at: datetime


# 검사 / 배포 / 버전 관리
class ValidationIssue(CamelModel):
    node_id: str | None = None
    edge_id: str | None = None
    message: str


class ValidationResult(CamelModel):
    valid: bool
    issues: list[ValidationIssue]


class DeployResult(CamelModel):
    version: int


class VersionInfo(CamelModel):
    version: int
    created_at: datetime
    active: bool

