from nodes.base import BaseNode
from nodes.impl.approval_draft import ApprovalDraftNode
from nodes.impl.condition import ConditionNode
from nodes.impl.employee_lookup import EmployeeLookupNode
from nodes.impl.file_upload import FileUploadNode
from nodes.impl.human_confirm import HumanConfirmNode
from nodes.impl.llm_verify import LlmVerifyNode
from nodes.impl.ocr_extract import OcrExtractNode
from nodes.impl.structured_extract import StructuredExtractNode
from nodes.impl.wait_approval import WaitApprovalNode
from nodes.impl.policy_check import PolicyCheckNode
from nodes.impl.rag_search import RagSearchNode


CATEGORIES = [
    {"code": "input", "name": "입력"},
    {"code": "ai", "name": "AI"},
    {"code": "rule", "name": "규칙·분기"},
    {"code": "human", "name": "사람 확인"},
    {"code": "output", "name": "문서·알림"},
]


# 노드 목록 (팔레트에 이 순서로 보임)
# 규정 근거 검색(rag_search)은 실제 구현 전까지 팔레트에서 숨김 (파일은 남겨둠)
_NODE_CLASSES: list[type[BaseNode]] = [
    FileUploadNode,          # 파일 업로드
    EmployeeLookupNode,      # 사원 조회 (user-service)
    OcrExtractNode,          # OCR 글자 인식
    StructuredExtractNode,   # 항목 추출 (DSPy)
    LlmVerifyNode,           # 자동 검증 (GMS 교차 추출)
    PolicyCheckNode,         # 규정 검토 (RAG + DSPy 판정)
    RagSearchNode,           # 규정 근거 검색 (RAG, LLM 없음)
    ConditionNode,           # 조건 분기
    HumanConfirmNode,        # 사람 확인 (interrupt)
    ApprovalDraftNode,       # 결재 상신 (approval-service)
    WaitApprovalNode,        # 결재 대기 (Kafka 결과 이벤트)
]


NODE_REGISTRY: dict[str, BaseNode] = {}


for node_class in _NODE_CLASSES:
    node = node_class()
    if node.node_type in NODE_REGISTRY:
        raise ValueError(f"노드 종류가 중복됩니다: {node.node_type}")
    NODE_REGISTRY[node.node_type] = node


def get_node_impl(node_type: str) -> BaseNode | None:
    return NODE_REGISTRY.get(node_type)


def catalog() -> list[dict]:
    return [node.to_catalog() for node in NODE_REGISTRY.values()]