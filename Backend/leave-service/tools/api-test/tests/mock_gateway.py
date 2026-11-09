"""로컬 HTTP 서버용 업무 상태. 실제 Gateway/MSA 구현을 대체하는 테스트 대역이다."""
import copy
import json
import uuid
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from http.cookies import SimpleCookie
from urllib.parse import parse_qs, urlsplit


def success(data, status=200):
    return status, {"success": True, "error": None, "data": copy.deepcopy(data)}


def error(status, code):
    return status, {"success": False, "error": {"code": code, "message": "private-server-message"}, "data": None}


class GatewayState:
    def __init__(self):
        self.next_id = 1000
        self.calls, self.failures = [], {}
        self.drop_path = None
        self.substitute = False
        self.users = {"admin@test": (900, 1, 5), "ordinary@test": (103, 2, 1), "teamlead@test": (105, 2, 2), "approver@test": (105, 2, 2)}
        self.types = {
            1: {"leaveTypeId": 1, "name": "E2E_LEAVE_TEST_BASE_ANNUAL", "paid": True, "balanceMode": "ACCOUNT", "balanceType": {"balanceTypeId": 4, "name": "E2E_LEAVE_TEST_ANNUAL_BALANCE", "active": True}, "requestUnit": "MIXED", "maxMinutes": None, "isActive": True},
            2: {"leaveTypeId": 2, "name": "E2E_LEAVE_TEST_BASE_SPECIAL", "paid": True, "balanceMode": "NONE", "balanceType": None, "requestUnit": "MIXED", "maxMinutes": None, "isActive": True}}
        self.policies = {11: {"policyId": 11, "name": "E2E_LEAVE_TEST_BASE_POLICY", "active": 12}}
        self.versions = {12: {"policyVersionId": 12, "policyId": 11, "versionNo": 1, "baseType": "HIRE_DATE", "unitMinutes": 30, "expiryRule": "END_OF_VALIDITY"}}
        self.accruals = {12: [{"ruleId": 20, "policyVersionId": 12, "grantMinutes": 7200}]}
        self.rules = {(12, i): {"ruleId": i + 20, "policyVersionId": 12, "leaveTypeId": i, "isRequestable": True, "allowedUnits": "MIXED", "minMinutes": 240, "maxMinutes": None} for i in (1, 2)}
        self.assignments = [{"assignmentId": 8, "employeeId": 103, "policyId": 11, "effectiveFrom": "2020-01-01T00:00:00Z", "effectiveTo": None}]
        self.grants = [{"grantId": 6, "accountId": 5, "balanceTypeId": 4, "policyVersionId": 12, "grantKind": "MANUAL", "originalMinutes": 7200, "validFrom": "2020-01-01T00:00:00Z", "expiresOn": "2099-01-01T00:00:00Z"}]
        self.ledger = [{"entryId": 7, "accountId": 5, "grantId": 6, "eventType": "GRANT", "quantityDelta": 7200, "reservedDelta": 0, "idempotencyKey": "e2e:grant:LEAVE_TEST:103:initial", "reversesEntryId": None}]
        self.requests, self.documents = {}, {}

    def identifier(self):
        self.next_id += 1
        return self.next_id

    def balances(self):
        if not self.grants:
            return []
        q = sum(row["quantityDelta"] for row in self.ledger)
        r = sum(row["reservedDelta"] for row in self.ledger)
        return [{"accountId": 5, "balanceTypeId": 4, "quantityMinutes": q, "reservedMinutes": r, "remainingMinutes": q - r}]

    def entry(self, request_id, event, q, r):
        self.ledger.append({"entryId": self.identifier(), "accountId": 5, "grantId": 6, "eventType": event,
                            "quantityDelta": q, "reservedDelta": r, "leaveReqId": request_id, "idempotencyKey": str(uuid.uuid4()), "reversesEntryId": None})

    def dispatch(self, method, uri, body, actor):
        path = urlsplit(uri).path
        if (method, path) in self.failures:
            return error(*self.failures[(method, path)])
        if path.startswith("/api/v1/leaves/internal/"):
            request = self.requests[int(path.split("/")[-2])]
            leave_type = self.types[request["leaveTypeId"]]
            return success({"requestId": request["requestId"], "employeeId": 103, "leaveTypeName": leave_type["name"], "isPaid": leave_type["paid"], "dates": request["dates"]})
        if actor is None:
            return error(401, "AUTH_001")
        if path.startswith("/api/v1/employees"):
            if parse_qs(urlsplit(uri).query).get("deptId"):
                return success({"employees": [{"empId": 103, "deptId": 2, "status": "EMPLOYED"}, {"empId": 105, "deptId": 2, "status": "EMPLOYED"}]})
            identity = next(value for value in self.users.values() if value[0] == actor)
            return success({"empId": actor, "deptId": identity[1], "dutyId": identity[2], "status": "EMPLOYED", "role": "USER"})
        if path.startswith("/api/v1/duties/"):
            return success({"name": {1: "팀원", 2: "팀장", 5: "휴가관리자"}[int(path.split("/")[-1])]})
        if path == "/api/v1/attn/holidays":
            return success([{"date": "2099-01-01", "type": "SUBSTITUTE"}] if self.substitute else [])
        if path == "/api/v1/attn/me/monthly":
            return success({"days": [{"workDate": d["leaveDate"], "leaves": [{"leaveTypeName": self.types[r["leaveTypeId"]]["name"], "portion": d["portion"], "isPaid": self.types[r["leaveTypeId"]]["paid"]}] if r["status"] == "APPROVED" else []} for r in self.requests.values() for d in r["dates"]]})
        if path.startswith("/api/v1/documents/"):
            if "/by-request/" in path:
                return success(next(d for d in self.documents.values() if d["requestKey"] == path.split("/")[-1]))
            document = self.documents[int(path.split("/")[4])]
            if method == "GET":
                return success(document)
            action = path.split("/")[-1]
            if (action == "cancel" and actor != 103) or (action != "cancel" and actor != 105):
                return error(403, "AUTH_009")
            status = {"approve": "APPROVED", "reject": "REJECTED", "cancel": "CANCEL"}[action]
            document["status"] = status
            request = self.requests[document["sourceId"]]
            request["status"] = "WITHDRAWN" if action == "cancel" else status
            minutes = request["totalDeductMinutes"]
            if minutes:
                self.entry(request["requestId"], "CONSUME" if action == "approve" else "RELEASE", -minutes if action == "approve" else 0, -minutes)
            return success(None)
        path = path.removeprefix("/api/v1/leaves")
        if path.startswith("/admin/") and actor != 900:
            return error(403, "AUTH_009")
        if path in ("/admin/types", "/types"):
            if method == "POST":
                identifier = self.identifier()
                self.types[identifier] = {"leaveTypeId": identifier, "name": body["name"], "paid": body["isPaid"], "balanceMode": body["balanceMode"], "balanceType": {"balanceTypeId": 4, "name": "E2E_LEAVE_TEST_ANNUAL_BALANCE", "active": True} if body["balanceTypeId"] else None, "requestUnit": "MIXED", "isActive": True, "maxMinutes": None}
                return success(self.types[identifier], 201)
            if path == "/types":
                if not self.assignments:
                    return error(404, "LEAVE_010")
                current = self.policies[self.assignments[-1]["policyId"]]["active"]
                return success([{"leaveTypeId": rule["leaveTypeId"], "allowedUnits": "MIXED"} for (version, _), rule in self.rules.items() if version == current])
            return success(list(self.types.values()))
        if path.startswith("/admin/types/"):
            row = self.types[int(path.split("/")[-1])]
            row.update(body)
            return success(row)
        if path == "/admin/policies":
            if method == "POST":
                identifier = self.identifier()
                self.policies[identifier] = {"policyId": identifier, "name": body["name"], "active": None}
                return success({"policyId": identifier, "name": body["name"]}, 201)
            return success([{"policyId": r["policyId"], "name": r["name"]} for r in sorted(self.policies.values(), key=lambda r: r["policyId"])])
        if path.startswith("/admin/policies/"):
            parts = path.split("/")
            policy = self.policies[int(parts[3])]
            if len(parts) == 4:
                if body:
                    policy.update(body)
                return success({"policyId": policy["policyId"], "name": policy["name"]})
            if parts[4] == "active-version":
                policy["active"] = body["versionId"]
                return success({"policyId": policy["policyId"], "activePolicyVersionId": body["versionId"]})
            if len(parts) == 5:
                rows = [r for r in self.versions.values() if r["policyId"] == policy["policyId"]]
                if method == "POST":
                    identifier = self.identifier()
                    self.versions[identifier] = {**body, "policyVersionId": identifier, "policyId": policy["policyId"], "versionNo": len(rows) + 1}
                    return success({**self.versions[identifier], "isActive": False}, 201)
                return success([{**r, "isActive": r["policyVersionId"] == policy["active"]} for r in sorted(rows, key=lambda r: r["versionNo"])])
            version_id = int(parts[5])
            if version_id not in self.versions:
                return error(404, "LEAVE_005")
            if len(parts) == 6:
                if method == "DELETE":
                    del self.versions[version_id]
                    return success(None)
                if body:
                    self.versions[version_id].update(body)
                return success({**self.versions[version_id], "isActive": version_id == policy["active"]})
            if parts[6] == "accrual-rules":
                if method == "PUT":
                    self.accruals[version_id] = [{**r, "ruleId": self.identifier(), "policyVersionId": version_id} for r in body["rules"]]
                return success(self.accruals.get(version_id, []))
            if parts[6] == "type-rules":
                if method == "PUT":
                    type_id = int(parts[7])
                    self.rules[(version_id, type_id)] = {**body, "ruleId": self.identifier(), "policyVersionId": version_id, "leaveTypeId": type_id}
                    return success(self.rules[(version_id, type_id)])
                return success([r for (v, _), r in self.rules.items() if v == version_id])
        if path.endswith("/policy-assignments"):
            if method == "POST":
                if self.assignments:
                    return error(409, "LEAVE_009")
                row = {"assignmentId": self.identifier(), "employeeId": 103, "policyId": body["policyId"], "effectiveFrom": datetime.now(timezone.utc).isoformat(), "effectiveTo": None}
                self.assignments.append(row)
                return success(row, 201)
            return success(self.assignments)
        if path == "/me/policy":
            if not self.assignments:
                return error(404, "LEAVE_010")
            policy = self.policies[self.assignments[-1]["policyId"]]
            return success({"policyId": policy["policyId"], "policyName": policy["name"], "policyVersionId": policy["active"]})
        if path.endswith("/balances") and not path.startswith("/departments/"):
            return success(self.balances())
        if path == "/me/summary":
            return success({"asOf": datetime.now(timezone.utc).isoformat(), "balances": self.balances()})
        if path.endswith("/grants"):
            return success(self.grants)
        if path.endswith("/ledger"):
            return success(self.ledger)
        if path.endswith("/adjustments"):
            self.entry(None, "ADJUST", body["quantityDelta"], 0)
            return success({**body, "entryId": self.ledger[-1]["entryId"], "reservedDelta": 0, "actorEmpId": 900}, 201)
        if path.endswith("/preview"):
            return success({"requestable": True, "validationStatus": "REQUESTABLE", "holidayVerification": "VERIFIED", "dates": body["dates"]})
        if path == "/me/requests" and method == "POST":
            identifier, document_id = self.identifier(), self.identifier()
            minutes = sum(480 if d["portion"] == "FULL" else 240 for d in body["dates"]) if self.types[body["leaveTypeId"]]["balanceMode"] == "ACCOUNT" else 0
            self.requests[identifier] = {**body, "requestId": identifier, "employeeId": 103, "status": "SUBMITTED", "approvalDocId": document_id, "totalDeductMinutes": minutes}
            self.documents[document_id] = {"documentId": document_id, "requestKey": str(uuid.uuid4()), "formType": "LEAVE", "sourceType": "LEAVE", "sourceId": identifier, "drafterId": 103, "status": "INPROGRESS", "approvalLines": [{"lineId": 1, "step": 1, "role": "APPROVER", "empId": 105, "actedAt": None}]}
            if minutes:
                self.entry(identifier, "RESERVE", 0, minutes)
            return success({"requestId": identifier, "status": "SUBMITTED", "approvalCreationStatus": "LINKED", "approvalDocId": document_id}, 201)
        if path in ("/me/requests", "/admin/requests"):
            return success(list(self.requests.values()))
        if path.endswith("/cancel"):
            return error(503, "LEAVE_020")
        if path.startswith(("/me/requests/", "/admin/requests/")):
            detail = copy.deepcopy(self.requests[int(path.split("/")[-1])])
            # 실제 LeaveRequestDateResponse는 LocalDate가 아닌 OffsetDateTime이다.
            for day in detail["dates"]:
                seoul_midnight = datetime.fromisoformat(day["leaveDate"] + "T00:00:00+09:00")
                day["leaveDate"] = seoul_midnight.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
            return success(detail)
        if path.startswith("/departments/"):
            if path.endswith("/requests"):
                return success([{**r, "dates": r["dates"]} for r in self.requests.values() if r["status"] == "APPROVED"])
            if actor != 105:
                return error(403, "AUTH_009")
            return success({"departmentId": 2, "asOf": datetime.now(timezone.utc).isoformat(), "employees": [{"employeeId": 103, "balances": self.balances()}]})
        raise AssertionError("Mock 경로 누락: " + method + " " + path)


def make_server(state):
    class Handler(BaseHTTPRequestHandler):
        def handle_request(self):
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))) or "null")
            cookie = SimpleCookie(self.headers.get("Cookie", ""))
            token = cookie.get("accessToken")
            actor = int(token.value.removeprefix("session-")) if token else None
            state.calls.append({"method": self.command, "path": self.path, "actor": actor, "body": body,
                                "cookie": self.headers.get("Cookie"), "key": self.headers.get("Idempotency-Key")})
            if self.path == "/api/v1/auth/login":
                if not isinstance(body, dict) or set(body) != {"email", "pass"} or body.get("pass") != "private-password" or body.get("email") not in state.users:
                    status, envelope = error(401, "AUTH_001")
                else:
                    actor = state.users[body["email"]][0]
                    status, envelope = success({"employeeId": actor})
                self.send_response(status)
                if status == 200:
                    self.send_header("Set-Cookie", f"accessToken=session-{actor}; Path=/; HttpOnly")
            else:
                status, envelope = state.dispatch(self.command, self.path, body, actor)
                if state.drop_path == (self.command, urlsplit(self.path).path):
                    self.close_connection = True
                    return
                self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps(envelope).encode())
        do_GET = do_POST = do_PATCH = do_PUT = do_DELETE = handle_request
        def log_message(self, *args):
            pass
    return ThreadingHTTPServer(("127.0.0.1", 0), Handler)
