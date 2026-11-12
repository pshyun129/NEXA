"""근태 조회 노드: 요청자 본인의 그날 출퇴근·휴일·근태이상을 attendance-service에서 가져옴.

쓰임: 규정 검토의 사실 확인 (야근 식대 = 오후 7시 이후까지 근무했는지, 휴일 근무 식대 = 회사 휴일인지)
      → 영수증 결제 시각으로 추정하던 것을 실제 근태 기록으로 바꿈
주의: 요청자 토큰으로 호출 → 결재 대기(Kafka 재개) 뒤에는 토큰이 없어서 쓸 수 없음 (결재 전에 배치)
영수증 여러 장: 앞 노드의 receipts에 나온 거래일을 날짜마다 한 번씩 조회해 by_date로 넘긴다 (규정 검토가 장마다 그날 기록을 씀)
"""
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime

from clients.attendance import get_my_daily
from engine.tokens import get_token
from nodes.base import BaseNode, NodeContext, NodeResult, latest_output
from nodes.impl.calculate import _date

LATE_WORK_HOUR = 19     # 경비 규정 제8조①: 야근 식대는 오후 7시 이후까지 근무한 경우
MAX_DATES = 400         # 한 실행에서 조회할 날짜 수 상한


def _hhmm(value: str | None) -> str | None:
    """'2026-11-04T20:31:00+09:00' → '20:31' (attendance-service가 서울 시간으로 줌)"""
    if not value:
        return None
    return datetime.fromisoformat(value).strftime("%H:%M")


class AttendanceLookupNode(BaseNode):
    node_type = "attendance_lookup"
    name = "근태 조회"
    category = "input"
    description = "요청자의 그날 출퇴근 시각·회사 휴일 여부·근태이상을 조회 (attendance-service)"
    config_fields = [
        {"key": "date", "label": "날짜 (예: {{n3.fields.date}}, 비우면 오늘)", "type": "ref", "required": False},
        {"key": "receipts", "label": "여러 장 추출·검증 노드 (예: {{n4}}, 비우면 가장 최근 · 장마다 거래일 조회)",
         "type": "ref", "required": False},
    ]
    branches = ["기록 있음", "기록 없음"]
    output_vars = [
        {"key": "work_date", "label": "근무일", "type": "text"},
        {"key": "is_holiday", "label": "회사 휴일 여부", "type": "boolean"},
        {"key": "clock_in", "label": "출근 시각", "type": "text"},
        {"key": "clock_out", "label": "퇴근 시각", "type": "text"},
        {"key": "worked_after_19", "label": "오후 7시 이후까지 근무", "type": "boolean"},
        {"key": "anomalies", "label": "근태이상", "type": "list"},
        {"key": "by_date", "label": "날짜별 근태 (여러 장일 때)", "type": "object"},
    ]

    @staticmethod
    def _day(work_date: str, token) -> dict:
        daily = get_my_daily(work_date, token)
        clock_out = _hhmm(daily.get("clockOut"))
        return {
            "work_date": work_date,
            "is_holiday": bool(daily.get("isHoliday")),
            "clock_in": _hhmm(daily.get("clockIn")),
            "clock_out": clock_out,
            # 퇴근 기록이 없으면 None (모름) → 규정 검토가 추측하지 않게 False로 만들지 않음
            "worked_after_19": None if clock_out is None else int(clock_out[:2]) >= LATE_WORK_HOUR,
            "anomalies": daily.get("anomalies") or [],
            "work_group": daily.get("workGroupName"),
            "has_record": daily.get("clockIn") is not None,
        }

    def run(self, ctx: NodeContext) -> NodeResult:
        token = get_token(ctx.run_id)
        source = ctx.config.get("receipts") or latest_output(ctx.outputs, "receipts") or {}
        receipts = source.get("receipts") if isinstance(source, dict) else None
        dates = []
        for r in receipts or []:
            d = (r.get("fields") or {}).get("date")
            try:
                d = date.fromisoformat(str(d)).isoformat()
            except ValueError:
                continue                                   # 날짜를 못 읽은 장은 규정 검토가 '확인 필요'로
            if d not in dates:
                dates.append(d)

        raw = ctx.config.get("date")
        if len(dates) >= 2:
            if len(dates) > MAX_DATES:
                raise ValueError(f"근태는 한 번에 {MAX_DATES}일까지 조회할 수 있습니다 (거래일 {len(dates)}일)")
            with ThreadPoolExecutor(max_workers=8) as pool:
                days = list(pool.map(lambda d: self._day(d, token), dates))
            by_date = dict(zip(dates, days))
            first = by_date[dates[0]]
        else:
            work_date = _date(raw, "날짜") if raw not in (None, "") else (
                date.fromisoformat(dates[0]) if dates else date.today())   # 날짜 형식 해석은 계산 노드와 같게
            first = self._day(work_date.isoformat(), token)
            by_date = {first["work_date"]: first}
        has_record = any(d["has_record"] for d in by_date.values())
        return NodeResult(output={**first, "by_date": by_date}, branch="기록 있음" if has_record else "기록 없음")