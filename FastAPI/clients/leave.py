"""leave-service 클라이언트 (휴가)."""
from clients.base import call
from core.config import settings

SERVICE = "leave-service"


def get_my_balances(token: str | None) -> list[dict]:
    """요청자 본인의 휴가 잔액: [{accountId, balanceTypeId, balanceTypeName, quantityMinutes, reservedMinutes, remainingMinutes}]
    reserved = 신청했지만 아직 결재 중인 시간 (remaining에서 이미 빠져 있음)"""
    return call(SERVICE, settings.leave_service_url, "GET", "/api/v1/leaves/me/balances", token=token) or []