"""사원 조회 노드: 실행을 요청한 사원의 정보·소속 부서·상급자를 user-service에서 가져옴."""
from clients.user import get_employee, get_org_chain
from core.database import SessionLocal
from engine.tokens import get_token
from nodes.base import BaseNode, NodeContext, NodeResult
from runs.models import Run


def _requester_id(run_id: int) -> int:
    """실행을 요청한 사원 ID (실행 버튼을 누를 때 토큰의 sub로 저장됨)"""
    with SessionLocal() as db:
        run = db.get(Run, run_id)
        if run is None or not run.requested_by:
            raise ValueError("실행 요청자 정보가 없습니다")
        return int(run.requested_by)


def _find_manager(chain: list[dict], employee_id: int) -> int | None:
    """소속 부서부터 위로 올라가며 본인이 아닌 첫 부서장 (팀원 → 팀장, 팀장 → 본부장)"""
    for unit in chain:
        head = unit.get("headEmpId")
        if head and head != employee_id:
            return head
    return None


class EmployeeLookupNode(BaseNode):
    node_type = "employee_lookup"
    name = "사원 조회"
    category = "input"
    description = "실행을 요청한 사원의 정보와 소속 부서, 상급자를 조회"
    config_fields = []
    branches = []
    output_vars = [
        {"key": "employee", "label": "사원 정보", "type": "object"},
        {"key": "dept_name", "label": "부서명", "type": "text"},
        {"key": "manager_id", "label": "상급자 사원 ID", "type": "number"},
    ]

    def run(self, ctx: NodeContext) -> NodeResult:
        token = get_token(ctx.run_id)
        employee_id = _requester_id(ctx.run_id)
        employee = get_employee(employee_id, token)
        chain = get_org_chain(employee_id, token).get("chain") or []

        return NodeResult(output={
            # 다음 노드에 필요한 것만 (로그인 ID 등은 뺌)
            "employee": {k: employee.get(k) for k in ("empId", "empNo", "name", "deptId", "gradeId", "dutyId")},
            "dept_name": chain[0]["deptName"] if chain else None,
            "manager_id": _find_manager(chain, employee_id),
        })