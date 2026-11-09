"""휴가 잔액 조회 노드: 요청자 본인의 휴가 종류별 남은 시간을 leave-service에서 가져옴.

쓰임: 휴가 신청 전 잔액 확인 → 조건 분기 ("남은 일수 미만 신청 일수" → 신청 안내 / 사람 확인)
단위: leave-service는 분 단위 → 일수로도 바꿔서 넘김 (1일 = 480분, 기본 근무그룹 1일 소정근로 8시간)
분기: 잔액 있음 / 잔액 없음 (휴가 정책이 배정되지 않은 사원·찾는 종류가 없는 경우 → 실패 대신 분기, 2026-11-06 실서버 E2E에서 발견)
"""
from clients.leave import get_my_balances
from engine.tokens import get_token
from nodes.base import BaseNode, NodeContext, NodeResult

MINUTES_PER_DAY = 480


def _days(minutes) -> float:
    return round((minutes or 0) / MINUTES_PER_DAY, 2)


class LeaveBalanceNode(BaseNode):
    node_type = "leave_balance"
    name = "휴가 잔액 조회"
    category = "input"
    description = "요청자의 휴가 종류별 남은 일수를 조회 (leave-service)"
    config_fields = [
        {"key": "balance_type", "label": "휴가 종류 (예: 연차, 비우면 첫 번째)", "type": "text", "required": False},
    ]
    branches = ["잔액 있음", "잔액 없음"]
    output_vars = [
        {"key": "remaining_days", "label": "남은 일수 (선택한 종류, 없으면 0)", "type": "number"},
        {"key": "remaining_minutes", "label": "남은 시간(분)", "type": "number"},
        {"key": "balance_type", "label": "휴가 종류", "type": "text"},
        {"key": "balances", "label": "전체 잔액", "type": "list"},
        {"key": "message", "label": "안내 (잔액이 없을 때 이유)", "type": "text"},
    ]

    def run(self, ctx: NodeContext) -> NodeResult:
        rows = get_my_balances(get_token(ctx.run_id))
        balances = [{"type": b.get("balanceTypeName"), "remaining_days": _days(b.get("remainingMinutes")),
                     "remaining_minutes": b.get("remainingMinutes"), "reserved_days": _days(b.get("reservedMinutes"))}
                    for b in rows]

        wanted = str(ctx.config.get("balance_type") or "").strip()
        if wanted:
            picked = next((b for b in balances if wanted in (b["type"] or "")), None)
        else:
            picked = balances[0] if balances else None

        if picked is None:
            # 조회 자체는 성공 → 실행을 실패시키지 않고 '잔액 없음' 분기로 (뒤에서 사람 확인·안내로 연결)
            if not balances:
                message = "휴가 잔액 정보가 없습니다 (휴가 정책이 배정되지 않은 사원일 수 있음)"
            else:
                message = f"'{wanted}' 휴가 잔액이 없습니다 (있는 종류: {', '.join(b['type'] or '?' for b in balances)})"
            return NodeResult(output={"remaining_days": 0, "remaining_minutes": 0, "balance_type": wanted or None,
                                      "balances": balances, "message": message}, branch="잔액 없음")

        return NodeResult(output={
            "remaining_days": picked["remaining_days"], "remaining_minutes": picked["remaining_minutes"],
            "balance_type": picked["type"], "balances": balances, "message": "",
        }, branch="잔액 있음")