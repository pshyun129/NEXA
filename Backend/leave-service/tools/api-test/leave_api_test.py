#!/usr/bin/env python3
"""python leave_api_test.py — .env를 읽어 사용자 여정을 한 번 실행한다."""
import json
import os
import re
import time
import uuid
from collections import Counter
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import urlencode, urlsplit

import requests


ROOT = Path(__file__).resolve().parent
SEOUL = timezone(timedelta(hours=9))
PREFIX = "/api/v1/leaves"
# 결과 누락 확인용 경로 목록이다. 실행 순서는 아래 user_journey()에 직접 작성한다.
P = "/admin/policies/{policyId}"
V = P + "/versions/{versionId}"
E = "/admin/employees/{employeeId}"
API_ROUTES = {
    "types": ("GET", "/admin/types"),
    "type_create": ("POST", "/admin/types"),
    "type_patch": ("PATCH", "/admin/types/{leaveTypeId}"),
    "policies": ("GET", "/admin/policies"),
    "policy": ("GET", P),
    "policy_create": ("POST", "/admin/policies"),
    "policy_patch": ("PATCH", P),
    "versions": ("GET", P + "/versions"),
    "version": ("GET", V),
    "version_create": ("POST", P + "/versions"),
    "version_patch": ("PATCH", V),
    "version_delete": ("DELETE", V),
    "accruals": ("GET", V + "/accrual-rules"),
    "accrual_put": ("PUT", V + "/accrual-rules"),
    "rules": ("GET", V + "/type-rules"),
    "rule_put": ("PUT", V + "/type-rules/{leaveTypeId}"),
    "activate": ("PUT", P + "/active-version"),
    "assignments": ("GET", E + "/policy-assignments"),
    "assign": ("POST", E + "/policy-assignments"),
    "admin_balances": ("GET", E + "/balances"),
    "admin_grants": ("GET", E + "/grants"),
    "admin_ledger": ("GET", E + "/ledger"),
    "admin_requests": ("GET", "/admin/requests"),
    "admin_request": ("GET", "/admin/requests/{requestId}"),
    "adjust": ("POST", E + "/adjustments"),
    "admin_cancel": ("POST", "/admin/requests/{requestId}/cancel"),
    "my_policy": ("GET", "/me/policy"),
    "requestable": ("GET", "/types"),
    "balances": ("GET", "/me/balances"),
    "summary": ("GET", "/me/summary"),
    "grants": ("GET", "/me/grants"),
    "ledger": ("GET", "/me/ledger"),
    "requests": ("GET", "/me/requests"),
    "request": ("GET", "/me/requests/{requestId}"),
    "preview": ("POST", "/me/requests/preview"),
    "submit": ("POST", "/me/requests"),
    "department_requests": ("GET", "/departments/{departmentId}/requests"),
    "department_balances": ("GET", "/departments/{departmentId}/balances"),
    "internal_dates": ("GET", "/internal/requests/{requestId}/dates"),
}
SETTINGS = {}
SESSIONS = {}
EMPLOYEES = {}
REPORT = {}
WRITES_ALLOWED = False


class Blocked(Exception):
    pass


def require(condition, reason):
    if not condition:
        raise AssertionError(reason)


def load_env(path):
    values = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8-sig").splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if "=" not in line:
                raise ValueError(".env에는 KEY=VALUE 형식만 사용하세요")
            key, value = line.split("=", 1)
            value = value.strip()
            if value.startswith(('"', "'")):
                if len(value) < 2 or value[-1] != value[0]:
                    raise ValueError(".env 따옴표를 닫아주세요")
                value = value[1:-1]
            values[key.strip()] = value
    values.update(os.environ)
    return values


def number(key):
    value = SETTINGS.get(key, "")
    return int(value) if value and value.isdecimal() and int(value) > 0 else None


def base_url(internal=False):
    value = SETTINGS.get("INTERNAL_LEAVE_URL" if internal else "BASE_URL", "").rstrip("/")
    if not value:
        raise Blocked("INTERNAL_LEAVE_URL 미설정" if internal else "BASE_URL 미설정")
    parts = urlsplit(value)
    require(parts.scheme in ("http", "https") and bool(parts.hostname)
            and not any((parts.username, parts.password, parts.query, parts.fragment)), "잘못된 서버 주소 형식")
    require(parts.scheme == "https" or parts.hostname in ("localhost", "127.0.0.1", "::1"), "외부 서버는 HTTPS 주소를 사용하세요")
    return value


def send(actor, method, path, body=None, key=None, internal=False, authenticated=True):
    session = SESSIONS.get(actor) if authenticated else requests.Session()
    if session is None:
        raise Blocked(f"{actor} 로그인 미완료")
    headers = {"Accept": "application/json"}
    if key:
        headers["Idempotency-Key"] = key
    try:
        # requests 기본 어댑터는 재시도 0회다. POST 응답 불명 시 자동 재전송하지 않는다.
        return session.request(method, base_url(internal) + path, json=body, headers=headers,
                               timeout=float(SETTINGS.get("HTTP_TIMEOUT", "10")), allow_redirects=False)
    except requests.RequestException:
        raise Blocked("HTTP 결과 불명: 자동 재전송하지 않음. 생성 ID·서버 기록을 수동 확인하세요") from None
    finally:
        if not authenticated:
            session.close()


def unpack(response, expected=(200,), fields=(), array=False, error=None):
    require(response.status_code in expected, f"예상 HTTP {expected}, 실제 {response.status_code}")
    try:
        envelope = response.json()
    except ValueError:
        raise AssertionError("JSON 응답이 아님; 본문은 저장하지 않음") from None
    require(isinstance(envelope, dict) and all(k in envelope for k in ("success", "error", "data")), "ApiResult 필드 누락")
    if error:
        require(envelope["success"] is False and envelope["data"] is None
                and isinstance(envelope["error"], dict) and envelope["error"].get("code") == error, "오류 ApiResult 또는 오류 코드 불일치")
        return None
    require(envelope["success"] is True and envelope["error"] is None, "성공 ApiResult 불일치")
    data = envelope["data"]
    require(isinstance(data, list) if array else (data is None or isinstance(data, dict)), "data 타입 불일치")
    rows = data if array else [data]
    for row in rows:
        if fields:
            require(isinstance(row, dict) and all(k in row for k in fields), "필수 응답 필드 누락: " + ", ".join(fields))
    return data


def record(key, outcome, reason, **details):
    row = REPORT["apis"][key]
    row["checks"].append({"outcome": outcome, "reason": reason, **details})
    priority = {"SKIP": 0, "PASS": 1, "BLOCKED": 2, "FAIL": 3}
    if row["outcome"] is None or priority[outcome] > priority[row["outcome"]]:
        row["outcome"] = outcome
    print(f"[{outcome}] {key}: {reason}")
    save_result()


def new_result_path():
    directory = ROOT / "result"
    directory.mkdir(parents=True, exist_ok=True)
    versions = [int(match.group(1)) for path in directory.iterdir()
                if (match := re.fullmatch(r"result_ver([0-9]+)\.json", path.name))]
    version = max(versions, default=0) + 1
    while True:
        path = directory / f"result_ver{version}.json"
        try:
            with path.open("x", encoding="utf-8"):
                pass
            return path
        except FileExistsError:
            version += 1


def save_result():
    # 결과 파일에는 설정, 로그인 Body, 응답 본문, 쿠키, JWT를 넣지 않는다.
    path = Path(REPORT["output"])
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({k: v for k, v in REPORT.items() if k != "output"}, ensure_ascii=False, indent=2), encoding="utf-8")


def api(key, method, path, actor="admin", body=None, expected=(200,), fields=(), array=False,
        error=None, ready=True, missing="선행 API 또는 초기 데이터 미준비", verify=None, idempotency=None):
    if not ready or "None" in path:
        record(key, "BLOCKED", missing)
        return None
    if method != "GET" and key != "preview" and not WRITES_ALLOWED:
        record(key, "SKIP", "ALLOW_WRITES·전용 직원 확인이 필요합니다")
        return None
    started = time.monotonic()
    response = None
    try:
        response = send(actor, method, PREFIX + path, body, idempotency, key == "internal_dates", key != "internal_dates")
        if response.status_code in (404, 503) and response.status_code not in expected:
            raise Blocked(f"HTTP {response.status_code}: 선행 데이터 또는 외부 연동 확인 필요")
        data = unpack(response, expected, fields, array, error)
        if verify:
            verify(data)
        record(key, "PASS", "HTTP·ApiResult·데이터 검증 완료", actualPath=PREFIX + path,
               expectedStatuses=list(expected), actualStatus=response.status_code,
               elapsedMs=round((time.monotonic() - started) * 1000, 2))
        return data
    except Blocked as failure:
        record(key, "BLOCKED", str(failure), actualPath=PREFIX + path,
               actualStatus=response.status_code if response is not None else None)
    except (AssertionError, KeyError, TypeError, ValueError) as failure:
        reason = str(failure) if isinstance(failure, AssertionError) else "응답 필드 타입 또는 형식 오류"
        code = None
        if response is not None:
            try:
                candidate = response.json().get("error", {}).get("code")
                if isinstance(candidate, str) and re.fullmatch(r"(?:COMMON|AUTH|LEAVE|LEAVE_AUTH)_[0-9]{3}", candidate):
                    code = candidate
            except (ValueError, AttributeError):
                pass
        record(key, "FAIL", reason, actualPath=PREFIX + path, expectedStatuses=list(expected),
               actualStatus=response.status_code if response is not None else None, errorCode=code)
    return None


def get(actor, path, fields=(), array=False):
    return unpack(send(actor, "GET", path), fields=fields, array=array)


def optional_get(actor, path, default, array=False):
    try:
        return get(actor, path, array=array)
    except (Blocked, AssertionError, KeyError, ValueError, TypeError):
        REPORT["preparation"].append("추가 조회 실패: " + path)
        return default


def login(actor):
    email, password = SETTINGS.get(actor.upper() + "_EMAIL"), SETTINGS.get(actor.upper() + "_PASS")
    if not email or not password:
        REPORT["logins"][actor] = {"outcome": "BLOCKED", "reason": f"{actor.upper()}_EMAIL/PASS 필요"}
        print(f"[BLOCKED] {actor}: 로그인 정보 미설정")
        return
    session = requests.Session()
    SESSIONS[actor] = session
    try:
        response = send(actor, "POST", "/api/v1/auth/login", {"email": email, "pass": password})
        data = unpack(response, fields=("employeeId",))
        require(any(cookie.name == "accessToken" and cookie.value for cookie in session.cookies), "accessToken 쿠키가 없음")
        employee = get(actor, f"/api/v1/employees/{data['employeeId']}", ("empId", "deptId", "dutyId", "status"))
        require(employee["empId"] == data["employeeId"] and employee["status"] == "EMPLOYED", "최신 직원 신원·재직 상태 불일치")
        if actor == "ordinary" and number("EXPECTED_EMPLOYEE_ID"):
            require(employee["empId"] == number("EXPECTED_EMPLOYEE_ID"), "전용 테스트 직원 ID 불일치")
        duty = get(actor, f"/api/v1/duties/{employee['dutyId']}", ("name",))
        if actor == "admin":
            require(duty["name"] == "휴가관리자", "관리자 직책은 휴가관리자여야 합니다")
        EMPLOYEES[actor] = employee
        REPORT["logins"][actor] = {"outcome": "PASS", "employeeId": employee["empId"]}
        print(f"[PASS] {actor}: 로그인 및 최신 직원 확인")
    except (Blocked, AssertionError, KeyError, ValueError, TypeError) as failure:
        session.close()
        SESSIONS.pop(actor, None)
        reason = str(failure) if isinstance(failure, (Blocked, AssertionError)) else "최신 직원 응답 필드 형식 오류"
        REPORT["logins"][actor] = {"outcome": "FAIL", "reason": reason}
        print(f"[FAIL] {actor}: 로그인·쿠키·직원 ID·재직·직책을 확인하세요")


def sorted_rows(rows, field):
    require([r[field] for r in rows] == sorted(r[field] for r in rows), field + " 오름차순 불일치")


def same(data, **expected):
    require(all(data.get(key) == value for key, value in expected.items()), "생성/수정 응답 값 불일치")


def balance(rows):
    matches = [r for r in rows if r["accountId"] == number("ACCOUNT_ID")]
    if len(matches) != 1:
        raise Blocked("SQL 02 준비 후 ACCOUNT_ID·GRANT_ID·INITIAL_GRANT_ENTRY_ID를 .env에 입력하세요")
    row = matches[0]
    q, r, remaining = (row[k] for k in ("quantityMinutes", "reservedMinutes", "remainingMinutes"))
    require(all(type(v) is int and 0 <= v <= 9223372036854775807 for v in (q, r, remaining))
            and r <= q and remaining == q - r, "잔액 정합성 불일치")
    return [q, r, remaining]


def check_balances(rows):
    sorted_rows(rows, "accountId")
    for row in rows:
        q, r, remaining = (row[k] for k in ("quantityMinutes", "reservedMinutes", "remainingMinutes"))
        require(all(type(v) is int and 0 <= v <= 9223372036854775807 for v in (q, r, remaining))
                and r <= q and remaining == q - r, "조회 잔액 정합성 불일치")


def current_balance():
    return balance(get("ordinary", PREFIX + "/me/balances", array=True))


def delta(before, after, quantity, reserved):
    require(after == [before[0] + quantity, before[1] + reserved, before[2] + quantity - reserved], "잔액 변화량 불일치: 다른 작업·만료·중복 처리를 확인하세요")


def poll(read, done, reason):
    deadline = time.monotonic() + float(SETTINGS.get("POLL_TIMEOUT", "30"))
    while True:
        value = read()
        if done(value):
            return value
        if time.monotonic() >= deadline:
            raise Blocked(reason)
        time.sleep(0.5)


def request_date_in_seoul(value):
    # 신청 상세는 OffsetDateTime이므로 서울 날짜로 변환한 뒤 요청의 LocalDate와 비교한다.
    require(isinstance(value, str), "신청 상세 날짜는 시각 문자열이어야 합니다")
    instant = datetime.fromisoformat(value.replace("Z", "+00:00"))
    require(instant.tzinfo is not None, "신청 상세 날짜에 시간대가 없습니다")
    return instant.astimezone(SEOUL).date().isoformat()


def choose_dates(grant):
    start = datetime.now(SEOUL).date() + timedelta(days=1)
    end = start + timedelta(days=44)
    holidays = get("ordinary", "/api/v1/attn/holidays?" + urlencode({"startDate": str(start), "endDate": str(end)}), array=True)
    require(all(h.get("type") in ("LEGAL", "COMPANY", "SUBSTITUTE") and h.get("date") for h in holidays), "공휴일 응답 누락 또는 형식 오류")
    if any(h["type"] == "SUBSTITUTE" for h in holidays):
        raise Blocked("Attendance SUBSTITUTE와 Leave LEGAL/COMPANY 계약 불일치")
    busy = {h["date"] for h in holidays}
    for request in get("ordinary", PREFIX + "/me/requests", array=True):
        if request["status"] in ("DRAFT", "SUBMITTING", "SUBMITTED", "APPROVED"):
            detail = get("ordinary", PREFIX + f"/me/requests/{request['requestId']}", ("dates",))
            busy.update(request_date_in_seoul(d["leaveDate"]) for d in detail["dates"])
    dates = []
    for offset in range(45):
        day = start + timedelta(days=offset)
        if day.weekday() >= 5 or str(day) in busy:
            continue
        midnight = datetime.combine(day, datetime.min.time(), SEOUL)
        if grant:
            valid_from = datetime.fromisoformat(grant["validFrom"].replace("Z", "+00:00"))
            expiry = datetime.fromisoformat(grant["expiresOn"].replace("Z", "+00:00")) if grant["expiresOn"] else None
            if midnight < valid_from or (expiry and midnight + timedelta(days=1) > expiry):
                continue
        dates.append(str(day))
    if len(dates) < 3:
        raise Blocked("공휴일·기존 신청·발생분 유효기간 제외 후 미래 날짜 3개가 필요합니다")
    return dates[:3]


def submit_and_settle(label, target, leave_type, leave_date, portion, account_ready):
    """신청 한 건의 외부 연동 검증. 새 요청 재전송·자동 복구는 하지 않는다."""
    result = {"scenario": label, "outcome": "BLOCKED", "reason": "정책·유형·날짜 미준비"}
    REPORT["integrations"].append(result)
    if not leave_type or not leave_date or not WRITES_ALLOWED:
        return None
    request_id = None
    try:
        minutes = (480 if portion == "FULL" else 240) if leave_type["balanceMode"] == "ACCOUNT" else 0
        if minutes and not account_ready:
            raise Blocked("정상 최초 GRANT·계정 확인 미완료")
        before = current_balance() if account_ready else None
        if minutes and before[2] < minutes:
            raise Blocked("가용 잔액 부족: GRANT를 추가 지급하지 않습니다")
        body = {"leaveTypeId": leave_type["leaveTypeId"], "reason": REPORT["runName"] + "_" + label,
                "dates": [{"leaveDate": leave_date, "portion": portion}]}
        preview = api("preview", "POST", "/me/requests/preview", "ordinary", body,
                      fields=("requestable", "validationStatus", "holidayVerification", "dates"),
                      verify=lambda d: require(d["requestable"] is True and d["validationStatus"] == "REQUESTABLE"
                                               and d["holidayVerification"] == "VERIFIED", "Preview 신청 불가 또는 검증 불명"))
        if not preview:
            raise Blocked("Preview 검증 실패: 실제 신청은 보내지 않음")
        key = str(uuid.uuid4())
        result.update(idempotencyKey=key, stage="Leave 신청")
        save_result()
        submitted = api("submit", "POST", "/me/requests", "ordinary", body, (200, 201, 202),
                        ("requestId", "status", "approvalCreationStatus", "approvalDocId"), idempotency=key)
        if not submitted:
            raise Blocked("신청 결과 불명: 재실행 전 서버 기록을 수동 확인하세요")
        request_id = submitted["requestId"]
        result.update(requestId=request_id, idempotencyKey=key)
        REPORT["createdIds"][label + "RequestId"] = request_id
        save_result()
        if submitted["status"] == "SUBMITTING":
            # HTTP 재전송 대신 기존 신청 GET으로만 기다린다.
            submitted = poll(lambda: get("ordinary", PREFIX + f"/me/requests/{request_id}"),
                             lambda d: d["status"] != "SUBMITTING", "SUBMITTING/UNKNOWN 유지: UUID 조회 복구는 수동 확인")
        require(submitted["status"] in ("SUBMITTED", target), "신청이 제출되지 않았음")
        doc_id = submitted.get("approvalDocId")
        require(type(doc_id) is int and doc_id > 0, "실제 결재 문서 ID 없음")
        result["approvalDocId"] = doc_id
        result["stage"] = "Approval 문서·UUID 조회"
        document = get("ordinary", f"/api/v1/documents/{doc_id}", ("documentId", "requestKey", "approvalLines"))
        same(document, documentId=doc_id, sourceType="LEAVE", formType="LEAVE", sourceId=request_id,
             drafterId=EMPLOYEES["ordinary"]["empId"])
        uuid.UUID(document["requestKey"])
        lookup = get("ordinary", "/api/v1/documents/by-request/" + document["requestKey"])
        same(lookup, documentId=doc_id)
        result["approvalRequestId"] = document["requestKey"]
        save_result()
        result["stage"] = "Approval 승인·반려·회수"
        if document["status"] == "INPROGRESS":
            if minutes:
                delta(before, current_balance(), 0, minutes)
            if target == "WITHDRAWN":
                require(not any(line.get("actedAt") for line in document["approvalLines"]), "결재 처리 후 기안자 회수 금지")
                unpack(send("ordinary", "POST", f"/api/v1/documents/{doc_id}/cancel"))
            else:
                lines = sorted([line for line in document["approvalLines"] if line["role"] == "APPROVER"], key=lambda line: line["step"])
                require(bool(lines), "실제 결재선 없음")
                for line in lines:
                    actor = next((a for a, employee in EMPLOYEES.items() if employee["empId"] == line["empId"]), None)
                    if not actor:
                        raise Blocked("실제 결재자 계정 필요: APPROVER_EMAIL/PASS 또는 APPROVER2_EMAIL/PASS 설정")
                    action = "approve" if target == "APPROVED" else "reject"
                    payload = None if action == "approve" else {"comment": REPORT["runName"] + "_반려"}
                    unpack(send(actor, "POST", f"/api/v1/documents/{doc_id}/{action}", payload))
                    if target == "REJECTED":
                        break
        expected_document = "CANCEL" if target == "WITHDRAWN" else target
        poll(lambda: get("ordinary", f"/api/v1/documents/{doc_id}"), lambda d: d["status"] == expected_document,
             "Approval 최종 상태 확인 시간 초과")
        result["stage"] = "Kafka 정산·원장 검증"
        detail = poll(lambda: get("ordinary", PREFIX + f"/me/requests/{request_id}"), lambda d: d["status"] == target,
                      "Kafka 정산 대기 시간 초과: Consumer·DLT 확인 필요")
        same(detail, requestId=request_id, employeeId=EMPLOYEES["ordinary"]["empId"], leaveTypeId=leave_type["leaveTypeId"])
        require([(request_date_in_seoul(d["leaveDate"]), d["portion"]) for d in detail["dates"]] == [(leave_date, portion)], "신청 날짜/반차 저장 불일치")
        ledger = [r for r in get("ordinary", PREFIX + "/me/ledger", array=True) if r.get("leaveReqId") == request_id]
        if minutes:
            reserved = [r for r in ledger if r["eventType"] == "RESERVE"]
            settled = [r for r in ledger if r["eventType"] == ("CONSUME" if target == "APPROVED" else "RELEASE")]
            require(len(reserved) == 1 and len(settled) == 1, "날짜 한 건의 중복/누락 원장")
            same(reserved[0], quantityDelta=0, reservedDelta=minutes, grantId=number("GRANT_ID"), accountId=number("ACCOUNT_ID"))
            same(settled[0], quantityDelta=-minutes if target == "APPROVED" else 0, reservedDelta=-minutes,
                 grantId=number("GRANT_ID"), accountId=number("ACCOUNT_ID"))
            delta(before, current_balance(), -minutes if target == "APPROVED" else 0, 0)
        else:
            require(not ledger, "NONE 유형에 원장이 생성됨")
            if before:
                delta(before, current_balance(), 0, 0)
        if SETTINGS.get("INTERNAL_LEAVE_URL"):
            internal = api("internal_dates", "GET", f"/internal/requests/{request_id}/dates", "ordinary",
                           fields=("requestId", "employeeId", "leaveTypeName", "isPaid", "dates"),
                           verify=lambda d: same(d, requestId=request_id, employeeId=EMPLOYEES["ordinary"]["empId"],
                                                 leaveTypeName=leave_type["name"], isPaid=leave_type["paid"], dates=body["dates"]))
            require(internal is not None, "내부 날짜 조회 검증 실패")
        result["stage"] = "Attendance 반영 조회"
        def attendance_matches():
            month = get("ordinary", "/api/v1/attn/me/monthly?" + urlencode({"month": leave_date[:7]}), ("days",))
            leaves = [item for day in month["days"] if day["workDate"] == leave_date for item in day["leaves"]
                      if item["leaveTypeName"] == leave_type["name"] and item["portion"] == portion and item["isPaid"] is leave_type["paid"]]
            return len(leaves) == (1 if target == "APPROVED" else 0)
        poll(attendance_matches, bool, "Attendance 근태 반영/미반영 확인 시간 초과")
        result.update(outcome="PASS", reason="Approval 문서·UUID·Kafka 정산·원장·Attendance 확인")
    except Blocked as failure:
        result["reason"] = str(failure)
    except (AssertionError, KeyError, ValueError, TypeError) as failure:
        result.update(outcome="FAIL", reason=str(failure) if isinstance(failure, AssertionError) else "외부 응답 필드 타입/형식 오류")
    print(f"[{result['outcome']}] {label}: {result['reason']}")
    save_result()
    return request_id


def test_admin_cancellation(request_id, approved_ok):
    """별도 명시 설정에서만 이번 실행으로 승인한 신청을 취소한다."""
    path = f"/admin/requests/{request_id}/cancel"
    reason = REPORT["runName"] + "_CANCEL"
    if SETTINGS.get("TEST_ADMIN_CANCEL_ENABLED", "false").lower() != "true":
        api("admin_cancel", "POST", path, body={"reason": reason}, expected=(503,), error="LEAVE_025",
            ready=request_id is not None)
        return
    result = {"scenario": "admin_cancel", "outcome": "BLOCKED", "reason": "이번 실행의 승인 연동 완료가 필요합니다"}
    REPORT["integrations"].append(result)
    if not approved_ok or request_id is None or not WRITES_ALLOWED:
        record("admin_cancel", "BLOCKED", result["reason"])
        return
    try:
        # 이번 실행의 승인 신청만 대상으로 하며 기존 업무 신청을 선택하지 않는다.
        detail = get("ordinary", PREFIX + f"/me/requests/{request_id}")
        require(detail["status"] == "APPROVED", "승인된 신청만 관리자 취소 검증 가능")
        document_id = detail["approvalDocId"]
        document = get("ordinary", f"/api/v1/documents/{document_id}")
        require(document["status"] == "APPROVED", "결재 승인 확인 필요")
        original = [entry for entry in get("ordinary", PREFIX + "/me/ledger", array=True)
                    if entry.get("leaveReqId") == request_id]
        consumes = {entry["entryId"]: entry for entry in original if entry["eventType"] == "CONSUME"}
        before = current_balance() if consumes else None
        result.update(requestId=request_id, approvalDocId=document_id)
        response = api("admin_cancel", "POST", path, body={"reason": reason}, expected=(200, 202),
                       fields=("requestId", "status", "adminCancelStatus", "adminCancelRequestId"))
        if response is None:
            raise Blocked("취소 응답 실패 또는 결과 불명: 자동 재전송하지 않습니다")
        result["cancelRequestId"] = response["adminCancelRequestId"]
        if response["status"] != "CANCELLED" or response["adminCancelStatus"] != "COMPLETED":
            raise Blocked("취소 처리 중/UNKNOWN: 저장된 UUID로 수동 복구 확인 필요")
        uuid.UUID(response["adminCancelRequestId"])
        same(response, requestId=request_id, cancelledBy=EMPLOYEES["admin"]["empId"], cancelReason=reason)
        after = [entry for entry in get("ordinary", PREFIX + "/me/ledger", array=True)
                 if entry.get("leaveReqId") == request_id]
        restores = [entry for entry in after if entry["eventType"] == "RESTORE"]
        require(len(restores) == len(consumes), "CONSUME별 RESTORE 누락 또는 중복")
        require({entry["reversesEntryId"] for entry in restores} == set(consumes), "RESTORE 원본 CONSUME 연결 불일치")
        for entry in restores:
            consume = consumes[entry["reversesEntryId"]]
            same(entry, accountId=consume["accountId"], grantId=consume["grantId"],
                 quantityDelta=-consume["quantityDelta"], reservedDelta=0)
        stored = {entry["entryId"]: entry for entry in after}
        require(all(stored.get(entry["entryId"]) == entry for entry in original), "기존 원장 변경 발생")
        if before is not None:
            delta(before, current_balance(), sum(entry["quantityDelta"] for entry in restores), 0)
        same(get("ordinary", PREFIX + f"/me/requests/{request_id}"), status="CANCELLED")
        same(get("ordinary", f"/api/v1/documents/{document_id}"), status="APPROVED")

        # 근태 월간 조회에서 동일 날짜·유형의 반영 해제를 확인한다.
        leave_type = next(item for item in get("admin", PREFIX + "/admin/types", array=True)
                          if item["leaveTypeId"] == detail["leaveTypeId"])
        for day in detail["dates"]:
            leave_date = request_date_in_seoul(day["leaveDate"])
            def no_attendance_leave():
                month = get("ordinary", "/api/v1/attn/me/monthly?" + urlencode({"month": leave_date[:7]}))
                return not any(item["leaveTypeName"] == leave_type["name"] and item["portion"] == day["portion"]
                               for row in month["days"] if row["workDate"] == leave_date for item in row["leaves"])
            poll(no_attendance_leave, bool, "관리자 취소 후 Attendance 근태가 남아 있습니다")

        # 완료가 확정된 요청만 같은 사유로 한 번 재호출해 중복 복원 방지를 검증한다.
        repeated = api("admin_cancel", "POST", path, body={"reason": reason}, expected=(200,),
                       fields=("status", "adminCancelStatus", "adminCancelRequestId"))
        require(repeated is not None and repeated["adminCancelRequestId"] == response["adminCancelRequestId"]
                and repeated["adminCancelStatus"] == "COMPLETED", "완료 재요청 결과 불일치")
        final = [entry for entry in get("ordinary", PREFIX + "/me/ledger", array=True)
                 if entry.get("leaveReqId") == request_id]
        require(final == after, "완료 재요청에서 원장 추가/변경 발생")
        result.update(outcome="PASS", reason="Attendance 미반영·RESTORE·취소 상태·결재 승인 유지·중복 복원 방지 확인")
    except Blocked as failure:
        result["reason"] = str(failure)
        record("admin_cancel", "BLOCKED", result["reason"])
    except (AssertionError, KeyError, ValueError, TypeError, StopIteration) as failure:
        result.update(outcome="FAIL", reason=str(failure) if isinstance(failure, AssertionError) else "관리자 취소 응답/원장 형식 오류")
        record("admin_cancel", "FAIL", result["reason"])
    save_result()


def user_journey():
    """업무 순서대로 한 번 호출한다. 실패한 데이터에 의존하는 단계만 차단한다."""
    global WRITES_ALLOWED
    # 계정별 로그인과 세션 재사용
    for actor in ("admin", "ordinary", "teamlead", "approver", "approver2"):
        login(actor)
    employee = EMPLOYEES.get("ordinary", {})
    employee_id, department_id = employee.get("empId"), employee.get("deptId")
    WRITES_ALLOWED = (SETTINGS.get("ALLOW_WRITES", "false").lower() == "true"
                      and SETTINGS.get("DEDICATED_EMPLOYEE_CONFIRMED", "false").lower() == "true"
                      and SETTINGS.get("ENVIRONMENT") in ("ec2-dev", "local-test")
                      and number("EXPECTED_EMPLOYEE_ID") == employee_id and employee_id is not None)
    prefix = f"E2E_{SETTINGS.get('DATA_KEY', 'LEAVE_TEST')}_"
    name = REPORT["runName"]
    balance_type_id = number("BALANCE_TYPE_ID")
    if balance_type_id is None:
        REPORT["preparation"].append("먼저 init/01_balance_type.sql을 수동 실행하고 BALANCE_TYPE_ID를 .env에 입력하세요")

    # 기본 조회·권한. 관리자 실패 시에도 본인 조회는 뒤에서 독립적으로 실행한다.
    types = api("types", "GET", "/admin/types", array=True, fields=("leaveTypeId", "name", "paid", "balanceMode", "isActive"))
    api("types", "GET", "/admin/types", "ordinary", expected=(403,), error="AUTH_009", ready="ordinary" in EMPLOYEES)
    assignments = api("assignments", "GET", f"/admin/employees/{employee_id}/policy-assignments", array=True,
                      fields=("assignmentId", "policyId", "effectiveFrom", "effectiveTo"), ready=employee_id is not None)
    existing_requests = api("requests", "GET", "/me/requests", "ordinary", array=True, fields=("requestId", "status"))
    no_pending = existing_requests is not None and not any(r["status"] in ("DRAFT", "SUBMITTING", "SUBMITTED") for r in existing_requests)

    # 실행 전용 휴가 유형 생성·수정. 기존 마스터는 변경하지 않는다.
    type_body = {"name": name + "_ANNUAL", "isPaid": True, "balanceMode": "ACCOUNT" if balance_type_id else "NONE",
                 "balanceTypeId": balance_type_id, "requestUnit": "MIXED", "isActive": True}
    created_type = api("type_create", "POST", "/admin/types", body=type_body, expected=(201,), fields=("leaveTypeId", "name", "balanceType"),
                       verify=lambda d: same(d, name=type_body["name"], paid=True, balanceMode=type_body["balanceMode"], requestUnit="MIXED", isActive=True))
    type_id = created_type["leaveTypeId"] if created_type else None
    changed_name = name + "_ANNUAL_CHANGED"
    def check_type_change(data):
        same(data, leaveTypeId=type_id, name=changed_name)
        rows = get("admin", PREFIX + "/admin/types", array=True)
        require(any(t["leaveTypeId"] == type_id and t["name"] == changed_name for t in rows), "유형 수정 후 목록 반영 누락")
    changed_type = api("type_patch", "PATCH", f"/admin/types/{type_id}", body={"name": changed_name}, fields=("leaveTypeId", "name"), ready=created_type is not None, verify=check_type_change)
    special = api("type_create", "POST", "/admin/types", body={**type_body, "name": name + "_SPECIAL", "balanceMode": "NONE", "balanceTypeId": None},
                  expected=(201,), fields=("leaveTypeId", "name")) if created_type else None

    # 정책 그룹 → 버전 → 규칙 → 활성화. ID는 바로 다음 요청에 전달한다.
    policy = api("policy_create", "POST", "/admin/policies", body={"name": name + "_POLICY"}, expected=(201,), fields=("policyId", "name"), verify=lambda d: same(d, name=name + "_POLICY"))
    policy_id = policy["policyId"] if policy else None
    path = f"/admin/policies/{policy_id}"
    api("policy_patch", "PATCH", path, body={"name": name + "_POLICY_CHANGED"}, ready=policy is not None,
        fields=("policyId", "name"), verify=lambda d: same(d, policyId=policy_id, name=name + "_POLICY_CHANGED"))
    api("policy", "GET", path, ready=policy is not None, fields=("policyId", "name"),
        verify=lambda d: require(d["name"] == name + "_POLICY_CHANGED" and "note" not in d, "정책 이름 수정 미반영 또는 note 포함"))
    api("policies", "GET", "/admin/policies", array=True, fields=("policyId", "name"), verify=lambda d: sorted_rows(d, "policyId"))
    version_body = {"baseType": "HIRE_DATE", "unitMinutes": 30, "carryOverAllowed": False, "expiryRule": "END_OF_VALIDITY", "effectiveFrom": None, "effectiveTo": None}
    version = api("version_create", "POST", path + "/versions", body=version_body, expected=(201,), fields=("policyVersionId", "versionNo", "expiryRule"), ready=policy is not None,
                  verify=lambda d: same(d, expiryRule="END_OF_VALIDITY", unitMinutes=30))
    version_id = version["policyVersionId"] if version else None
    version_path = path + f"/versions/{version_id}"
    api("version_patch", "PATCH", version_path, body={"unitMinutes": 60}, ready=version is not None, fields=("policyVersionId", "unitMinutes"),
        verify=lambda d: same(d, policyVersionId=version_id, unitMinutes=60))
    api("version", "GET", version_path, ready=version is not None, fields=("policyVersionId", "isActive", "unitMinutes"), verify=lambda d: same(d, policyVersionId=version_id, unitMinutes=60, isActive=False))
    accruals = api("accrual_put", "PUT", version_path + "/accrual-rules", body={"rules": [{"minServiceMonths": 0, "maxServiceMonths": None, "frequency": "YEARLY", "grantMinutes": 7200, "isActive": True}]},
                   ready=version is not None, array=True, fields=("ruleId", "grantMinutes"), verify=lambda d: require(len(d) == 1 and d[0]["grantMinutes"] == 7200, "발생 규칙 저장 불일치"))
    api("accruals", "GET", version_path + "/accrual-rules", ready=version is not None, array=True, fields=("ruleId", "policyVersionId", "grantMinutes"),
        verify=lambda d: require(accruals is None or d == accruals, "발생 규칙 조회와 PUT 결과 불일치"))
    rule_body = {"isRequestable": True, "allowedUnits": "MIXED", "minMinutes": 240, "maxMinutes": None}
    rule = api("rule_put", "PUT", version_path + f"/type-rules/{type_id}", body=rule_body, ready=version is not None and changed_type is not None,
               fields=("ruleId", "leaveTypeId", "allowedUnits"), verify=lambda d: same(d, leaveTypeId=type_id, **rule_body))
    if special and version:
        api("rule_put", "PUT", version_path + f"/type-rules/{special['leaveTypeId']}", body=rule_body, fields=("ruleId", "leaveTypeId", "allowedUnits"))
    api("rules", "GET", version_path + "/type-rules", ready=version is not None, array=True, fields=("ruleId", "leaveTypeId", "allowedUnits"),
        verify=lambda d: require(not rule or any(r["ruleId"] == rule["ruleId"] for r in d), "유형 규칙 조회 누락"))
    def check_activation(data):
        same(data, policyId=policy_id, activePolicyVersionId=version_id)
        rows = get("admin", PREFIX + path + "/versions", array=True)
        require([r["policyVersionId"] for r in rows if r["isActive"]] == [version_id], "활성 포인터 반영 불일치")
    active = api("activate", "PUT", path + "/active-version", body={"versionId": version_id}, ready=rule is not None,
                 fields=("policyId", "activePolicyVersionId"), verify=check_activation)
    api("versions", "GET", path + "/versions", ready=policy is not None, array=True, fields=("policyVersionId", "versionNo", "isActive"), verify=lambda d: sorted_rows(d, "versionNo"))

    # DELETE는 이 실행에서 새로 만든 비활성·미사용 버전만 대상으로 한다.
    unused = api("version_create", "POST", path + "/versions", body=version_body, expected=(201,), fields=("policyVersionId",), ready=policy is not None)
    def check_deleted(data):
        require(data is None, "삭제 응답 data는 null이어야 합니다")
        unpack(send("admin", "GET", PREFIX + path + f"/versions/{unused['policyVersionId']}"), (404,), error="LEAVE_005")
    api("version_delete", "DELETE", path + f"/versions/{unused['policyVersionId'] if unused else None}", ready=unused is not None, verify=check_deleted)

    # 최초 배정만 생성한다. 기존 배정이 있으면 동일 시작 시각의 409 거부와 보존을 검증한다.
    if assignments == []:
        def check_assignment(data):
            same(data, employeeId=employee_id, policyId=policy_id)
            rows = get("admin", PREFIX + f"/admin/employees/{employee_id}/policy-assignments", array=True)
            require(len(rows) == 1 and rows[0]["assignmentId"] == data["assignmentId"], "배정 저장 후 조회 불일치")
        api("assign", "POST", f"/admin/employees/{employee_id}/policy-assignments", body={"policyId": policy_id}, expected=(201,),
            fields=("assignmentId", "employeeId", "policyId"), ready=active is not None and balance_type_id is not None and no_pending,
            missing="SQL 01 잔액 유형과 활성 정책·전용 직원이 필요합니다", verify=check_assignment)
    elif assignments:
        now = datetime.now(timezone.utc)
        current = [a for a in assignments if datetime.fromisoformat(a["effectiveFrom"].replace("Z", "+00:00")) <= now
                   and (a["effectiveTo"] is None or now < datetime.fromisoformat(a["effectiveTo"].replace("Z", "+00:00")))]
        own = False
        if len(current) == 1 and "admin" in EMPLOYEES:
            existing_group = optional_get("admin", PREFIX + f"/admin/policies/{current[0]['policyId']}", {})
            own = existing_group.get("name", "").startswith(prefix)
        def unchanged_assignments(data):
            require(get("admin", PREFIX + f"/admin/employees/{employee_id}/policy-assignments", array=True) == assignments, "충돌 요청이 기존 배정을 변경함")
        api("assign", "POST", f"/admin/employees/{employee_id}/policy-assignments", body={"policyId": current[0]["policyId"], "effectiveFrom": current[0]["effectiveFrom"]} if current else {},
            expected=(409,), error="LEAVE_009", ready=own and no_pending, missing="기존 배정을 자동 종료하지 않습니다. 전용 테스트 정책 확인 필요", verify=unchanged_assignments)

    # 본인·관리자 조회는 CRUD 성공과 독립적으로 진행한다.
    my_policy = api("my_policy", "GET", "/me/policy", "ordinary", fields=("policyId", "policyVersionId", "policyName"))
    requestable = api("requestable", "GET", "/types", "ordinary", array=True, fields=("leaveTypeId", "allowedUnits"))
    balances = api("balances", "GET", "/me/balances", "ordinary", array=True, fields=("accountId", "balanceTypeId", "quantityMinutes", "reservedMinutes", "remainingMinutes"), verify=check_balances)
    api("summary", "GET", "/me/summary", "ordinary", fields=("asOf", "balances"), verify=lambda d: require(isinstance(d["balances"], list) and datetime.fromisoformat(d["asOf"].replace("Z", "+00:00")).tzinfo is not None, "summary 형식 불일치"))
    grants = api("grants", "GET", "/me/grants", "ordinary", array=True, fields=("grantId", "accountId", "originalMinutes", "validFrom", "expiresOn"))
    ledger = api("ledger", "GET", "/me/ledger", "ordinary", array=True, fields=("entryId", "eventType", "quantityDelta", "reservedDelta"))
    for key, suffix, fields in (("admin_balances", "balances", ("accountId", "quantityMinutes", "reservedMinutes", "remainingMinutes")),
                                ("admin_grants", "grants", ("grantId", "accountId", "originalMinutes")),
                                ("admin_ledger", "ledger", ("entryId", "quantityDelta", "reservedDelta"))):
        api(key, "GET", f"/admin/employees/{employee_id}/{suffix}", ready=employee_id is not None, array=True, fields=fields,
            verify=check_balances if key == "admin_balances" else None)
    api("admin_requests", "GET", "/admin/requests", array=True, fields=("requestId", "status"))
    REPORT["createdIds"].update(policyId=policy_id, versionId=version_id, leaveTypeId=type_id)
    if my_policy and my_policy["policyName"].startswith(prefix):
        REPORT["initialGrantParameters"] = {"employeeId": employee_id, "balanceTypeId": balance_type_id,
                                            "policyId": my_policy["policyId"], "versionId": my_policy["policyVersionId"],
                                            "expectedPolicyName": my_policy["policyName"]}

    # SQL 기준 지급의 소유권과 현재 잔액을 확인한다. originalMinutes는 잔액에 더하지 않는다.
    account_ready, grant = False, None
    try:
        require(my_policy is not None and my_policy["policyName"].startswith(prefix), "본인 정책이 전용 테스트 정책이 아님")
        require(all(number(k) for k in ("ACCOUNT_ID", "GRANT_ID", "INITIAL_GRANT_ENTRY_ID", "BALANCE_TYPE_ID")), "SQL 02 실행 후 ACCOUNT_ID·GRANT_ID·INITIAL_GRANT_ENTRY_ID 필요")
        grant = next(g for g in (grants or []) if g["grantId"] == number("GRANT_ID"))
        same(grant, accountId=number("ACCOUNT_ID"), balanceTypeId=balance_type_id, policyVersionId=my_policy["policyVersionId"], grantKind="MANUAL")
        initial = next(r for r in (ledger or []) if r["entryId"] == number("INITIAL_GRANT_ENTRY_ID"))
        require(initial["eventType"] == "GRANT" and initial["quantityDelta"] > 0 and initial["reservedDelta"] == 0
                and initial.get("reversesEntryId") is None and not any(r.get("reversesEntryId") == initial["entryId"] for r in ledger), "정상 최초 GRANT 근거 없음")
        same(initial, accountId=number("ACCOUNT_ID"), grantId=number("GRANT_ID"), idempotencyKey=f"e2e:grant:{SETTINGS.get('DATA_KEY', 'LEAVE_TEST')}:{employee_id}:initial")
        balance(balances)
        account_ready = True
    except (AssertionError, Blocked, StopIteration, TypeError, KeyError):
        REPORT["preparation"].append("최초 지급 미준비/불일치: SQL 01 → 정책·배정 API → SQL 02 순서로 준비하고 .env의 지급 ID를 확인하세요")

    # ADJUST는 기존 전용 발생분에서 30분 감소 한 번만 검증한다.
    before_adjust = balance(balances) if account_ready else None
    def check_adjustment(data):
        same(data, accountId=number("ACCOUNT_ID"), grantId=number("GRANT_ID"), quantityDelta=-30, reservedDelta=0, actorEmpId=EMPLOYEES["admin"]["empId"])
        delta(before_adjust, current_balance(), -30, 0)
    adjusted = api("adjust", "POST", f"/admin/employees/{employee_id}/adjustments", body={"accountId": number("ACCOUNT_ID"), "grantId": number("GRANT_ID"), "quantityDelta": -30, "reason": name + "_ADJUST"},
                   expected=(201,), fields=("entryId", "quantityDelta", "reservedDelta", "actorEmpId"),
                   ready=account_ready and no_pending and before_adjust[2] >= 30, missing="정상 지급·가용 잔액 30분·진행 중 신청 없음이 필요합니다",
                   idempotency=str(uuid.uuid4()), verify=check_adjustment)

    # 현재 적용 정책의 유형으로 승인·반려·회수를 각각 한 번 검증한다.
    latest_types = optional_get("admin", PREFIX + "/admin/types", types or [], array=True) if "admin" in EMPLOYEES else (types or [])
    allowed_ids = {r["leaveTypeId"] for r in (requestable or []) if r["allowedUnits"] == "MIXED"}
    annual = next((t for t in latest_types if t["leaveTypeId"] in allowed_ids and t["name"].startswith(prefix)
                   and t["balanceMode"] == "ACCOUNT" and t["isActive"]), None)
    none_type = next((t for t in latest_types if t["leaveTypeId"] in allowed_ids and t["name"].startswith(prefix)
                      and t["balanceMode"] == "NONE" and t["isActive"]), None)
    dates = []
    try:
        if WRITES_ALLOWED and no_pending and my_policy and my_policy["policyName"].startswith(prefix):
            dates = choose_dates(grant if account_ready else None)
    except (Blocked, AssertionError, KeyError, ValueError, TypeError) as failure:
        REPORT["preparation"].append(str(failure) if isinstance(failure, (Blocked, AssertionError)) else "날짜·공휴일 응답 형식을 확인하세요")
    selected = annual if account_ready and before_adjust[2] - (30 if adjusted else 0) >= 480 else none_type
    # 조정 전송 후 결과가 불명확하면 새로운 신청을 보내지 않는다.
    adjustment_uncertain = any(c.get("actualStatus") is not None or "결과 불명" in c["reason"] for c in REPORT["apis"]["adjust"]["checks"]) and adjusted is None and account_ready and before_adjust[2] >= 30
    if adjustment_uncertain:
        dates = []
    approved = submit_and_settle("approved", "APPROVED", selected, dates[0] if dates else None, "FULL", account_ready)
    approved_ok = bool(REPORT["integrations"] and REPORT["integrations"][-1]["outcome"] == "PASS")
    rejected = submit_and_settle("rejected", "REJECTED", selected, dates[1] if len(dates) > 1 and approved_ok else None, "AM_HALF", account_ready)
    rejected_ok = bool(REPORT["integrations"][-1]["outcome"] == "PASS")
    withdrawn = submit_and_settle("withdrawn", "WITHDRAWN", selected, dates[2] if len(dates) > 2 and rejected_ok else None, "PM_HALF", account_ready)

    # 신청 상세와 내부 날짜, 부서 조회. 실패한 신청과 독립적인 조회는 계속한다.
    request_id = approved or rejected or withdrawn
    api("request", "GET", f"/me/requests/{request_id}", "ordinary", ready=request_id is not None,
        fields=("requestId", "employeeId", "dates", "status"), verify=lambda d: same(d, requestId=request_id, employeeId=employee_id))
    api("admin_request", "GET", f"/admin/requests/{request_id}", ready=request_id is not None, fields=("requestId", "dates", "status"), verify=lambda d: same(d, requestId=request_id))
    if not any(c["outcome"] == "PASS" for c in REPORT["apis"]["internal_dates"]["checks"]):
        api("internal_dates", "GET", f"/internal/requests/{request_id}/dates", ready=request_id is not None, fields=("requestId", "employeeId", "leaveTypeName", "isPaid", "dates"))
    def check_department(data):
        members = get("ordinary", "/api/v1/employees?" + urlencode({"deptId": department_id}), ("employees",))
        ids = {m["empId"] for m in members["employees"] if m["deptId"] == department_id and m["status"] == "EMPLOYED"}
        require(all(r["employeeId"] in ids and r["status"] == "APPROVED" for r in data), "타 부서 또는 미승인 신청 혼입")
        if approved_ok:
            require(any(r["requestId"] == approved for r in data), "승인 신청의 부서 일정 반영 누락")
    api("department_requests", "GET", f"/departments/{department_id}/requests", "ordinary", ready=department_id is not None,
        array=True, fields=("requestId", "employeeId", "status", "dates"), verify=check_department)
    def check_department_balances(data):
        same(data, departmentId=department_id)
        members = get("teamlead", "/api/v1/employees?" + urlencode({"deptId": department_id}), ("employees",))
        ids = {m["empId"] for m in members["employees"] if m["deptId"] == department_id and m["status"] == "EMPLOYED"}
        for employee_balances in data["employees"]:
            require(employee_balances["employeeId"] in ids, "부서 잔액에 타 부서 직원 혼입")
            for row in employee_balances["balances"]:
                require(0 <= row["reservedMinutes"] <= row["quantityMinutes"]
                        and row["remainingMinutes"] == row["quantityMinutes"] - row["reservedMinutes"], "직원별 잔액 정합성 불일치")
    api("department_balances", "GET", f"/departments/{department_id}/balances", "teamlead", ready=department_id is not None,
        fields=("departmentId", "asOf", "employees"), verify=check_department_balances)
    api("department_balances", "GET", f"/departments/{department_id}/balances", "ordinary", ready=department_id is not None, expected=(403,), error="AUTH_009")
    test_admin_cancellation(approved if approved_ok else request_id, approved_ok)
    for key, row in REPORT["apis"].items():
        if not row["checks"]:
            record(key, "BLOCKED", "초기 데이터 또는 앞선 신청 미준비")
    for reason in REPORT["preparation"]:
        print("[준비 필요] " + reason)
    if not account_ready and REPORT.get("initialGrantParameters"):
        parameters = REPORT["initialGrantParameters"]
        print("[SQL 02 입력] " + json.dumps(parameters, ensure_ascii=False))
        print("[SQL 02 이후] 출력된 accountId/grantId/initialGrantEntryId를 .env에 입력하세요")


def main(settings=None, output=None):
    global SETTINGS, SESSIONS, EMPLOYEES, REPORT, WRITES_ALLOWED
    configuration_error = None
    try:
        SETTINGS = dict(settings) if settings is not None else load_env(ROOT / ".env")
    except (OSError, ValueError):
        SETTINGS = {}
        configuration_error = ".env 파일 접근 권한 또는 KEY=VALUE·따옴표 형식을 확인하세요"
    SESSIONS, EMPLOYEES, WRITES_ALLOWED = {}, {}, False
    data_key = SETTINGS.get("DATA_KEY", "LEAVE_TEST")
    run_name = "E2E_" + data_key + "_" + datetime.now(SEOUL).strftime("%Y%m%d_%H%M%S_") + uuid.uuid4().hex[:8]
    REPORT = {"runName": run_name, "startedAt": datetime.now(timezone.utc).isoformat(), "output": str(output or new_result_path()),
              "logins": {}, "createdIds": {}, "preparation": [], "integrations": [],
              "apis": {k: {"method": method, "path": PREFIX + path, "outcome": None, "checks": []} for k, (method, path) in API_ROUTES.items()}}
    try:
        if configuration_error:
            raise Blocked(configuration_error)
        require(bool(re.fullmatch(r"[A-Za-z0-9_-]{1,20}", data_key)), "DATA_KEY는 ASCII 1~20자여야 합니다")
        base_url()
        user_journey()
    except (Blocked, AssertionError) as failure:
        REPORT["preparation"].append(str(failure))
    except Exception:
        REPORT["preparation"].append("예상 밖 도구 오류: 비밀정보 보호를 위해 예외 원문은 저장하지 않습니다")
        REPORT["fatalError"] = True
    finally:
        for key, row in REPORT["apis"].items():
            if row["outcome"] is None:
                record(key, "BLOCKED", "설정 또는 선행 단계 확인 필요")
        counts = Counter(row["outcome"] for row in REPORT["apis"].values())
        REPORT["summary"] = {"totalApis": len(API_ROUTES), **{kind: counts[kind] for kind in ("PASS", "FAIL", "SKIP", "BLOCKED")}}
        REPORT["finishedAt"] = datetime.now(timezone.utc).isoformat()
        save_result()
        for session in SESSIONS.values():
            session.close()
    print(json.dumps(REPORT["summary"], ensure_ascii=False))
    print("결과: " + REPORT["output"])
    return 1 if REPORT.get("fatalError") or any(r["outcome"] == "FAIL" for r in REPORT["apis"].values()) or any(r["outcome"] == "FAIL" for r in REPORT["integrations"]) or any(r["outcome"] == "FAIL" for r in REPORT["logins"].values()) else (
        2 if any(r["outcome"] == "BLOCKED" for r in REPORT["apis"].values()) or any(r["outcome"] == "BLOCKED" for r in REPORT["integrations"]) else 0)


if __name__ == "__main__":
    raise SystemExit(main())
