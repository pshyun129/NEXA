"""attendance-service 클라이언트 (근태)."""
from clients.base import call
from core.config import settings

SERVICE = "attendance-service"


def get_my_daily(work_date: str, token: str | None) -> dict:
    """요청자 본인의 그날 근태 (토큰의 sub = 사원):
    workDate, workGroupName, isHoliday(회사 달력, 대체휴일 포함), clockIn, clockOut(+09:00 시각), workTime, records, anomalies"""
    return call(SERVICE, settings.attendance_service_url, "GET", f"/api/v1/attn/me/daily/{work_date}", token=token)