"""user-service 클라이언트 (사원·조직)."""
from clients.base import call
from core.config import settings

SERVICE = "user-service"


def get_employee(employee_id: int, token: str | None) -> dict:
    """사원 정보: empId, empNo, name, role, deptId, positionId, gradeId, dutyId, status ..."""
    return call(SERVICE, settings.user_service_url, "GET", f"/api/v1/employees/{employee_id}", token=token)


def get_org_chain(employee_id: int, token: str | None) -> dict:
    """소속 부서 → 상위 부서 순: {employeeId, deptId, chain: [{deptId, deptName, deptType, headEmpId}]}"""
    return call(SERVICE, settings.user_service_url, "GET", f"/api/v1/employees/{employee_id}/org-chain", token=token)