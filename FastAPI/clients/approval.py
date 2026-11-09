"""approval-service 클라이언트 (결재 문서)."""
from uuid import UUID

from clients.base import call
from core.config import settings

SERVICE = "approval-service"
DOCUMENTS = "/api/v1/documents"


def create_document(
    *,
    title: str,
    form_type: str,
    token: str | None,
    source_type: str = "DIRECT",
    source_id: int | None = None,
    request_key: UUID | None = None,
    attributes: dict | None = None,
) -> dict:
    """문서 생성 + 상신. 결재선은 결재 서비스가 위임전결 규정으로 자동 결정한다.

    같은 request_key로 다시 부르면 새로 만들지 않고 기존 문서를 돌려줌 → 재시도해도 문서가 두 번 안 생김.
    반환: {documentId, docNo}
    """
    body: dict = {"title": title, "formType": form_type, "sourceType": source_type}
    if source_type != "DIRECT":
        body["sourceId"] = source_id  # 출처가 있으면 원본 ID 필수, DIRECT면 보내면 안 됨
    if request_key is not None:
        body["requestKey"] = str(request_key)
    if attributes:
        body["attributes"] = attributes  # 전결 판단용 (GENERAL은 amount)
    return call(SERVICE, settings.approval_service_url, "POST", DOCUMENTS, token=token, json=body)


def get_by_request_key(request_key: UUID, token: str | None) -> dict:
    """요청 키로 문서 조회. status: INPROGRESS / APPROVED / REJECTED / CANCEL"""
    return call(SERVICE, settings.approval_service_url, "GET", f"{DOCUMENTS}/by-request/{request_key}", token=token)


def preview_approval_line(form_type: str, token: str | None, **attributes) -> dict:
    """상신 전에 결재선 미리보기 (저장 안 함). 예: preview_approval_line("GENERAL", token, amount=17900)"""
    params = {"formType": form_type, **{k: v for k, v in attributes.items() if v is not None}}
    return call(SERVICE, settings.approval_service_url, "GET", f"{DOCUMENTS}/approval-line-preview", token=token, params=params)