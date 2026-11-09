"""로컬 연동 테스트용 가짜 팀원 서비스 (개발용, 커밋 안 함).

팀원 코드의 DTO·규칙을 그대로 흉내 낸 서버 하나로 아래를 제공한다.
  - user-service JWKS:  GET  /.well-known/jwks.json
  - user-service 사원:   GET  /api/v1/employees/{id}, /api/v1/employees/{id}/org-chain
  - approval-service:   POST /api/v1/documents, GET /api/v1/documents/by-request/{key}
  - 테스트용 결재 처리:   POST /_dev/documents/{id}/approve | reject   (결재자 대신 승인/반려)

시드 데이터는 user-service dev seed와 같음 (101~109, 104 강도윤 = 개발팀 과장).
토큰은 scripts/.tokens.sh 에 저장 → source scripts/.tokens.sh

  python -u scripts/fake_services.py
"""
import json
import re
import time
import uuid
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import jwt
from cryptography.hazmat.primitives.asymmetric import rsa
from jwt.algorithms import RSAAlgorithm

PORT = 18999
KID = "local-test-key"

# ---------------------------------------------------------------- 키·토큰
key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
jwk = json.loads(RSAAlgorithm.to_jwk(key.public_key()))
jwk.update({"kid": KID, "use": "sig", "alg": "RS256"})
JWKS = {"keys": [jwk]}


def make_token(sub: int, role="USER", exp_in=1800, **extra) -> str:
    now = int(time.time())
    payload = {"sub": str(sub), "type": "access", "jti": str(uuid.uuid4()), "iat": now, "exp": now + exp_in, "role": role, **extra}
    return jwt.encode(payload, key, algorithm="RS256", headers={"kid": KID})


# ---------------------------------------------------------------- 시드 (user-service dev seed와 동일)
DEPTS = {  # dept_id: (이름, 유형, 상위, 부서장)
    13: ("DEV COMPANY", "COMPANY", None, 109),
    10: ("경영지원본부", "DIVISION", 13, 107), 11: ("기술본부", "DIVISION", 13, 108), 12: ("영업본부", "DIVISION", 13, None),
    1: ("인사팀", "TEAM", 10, 102), 2: ("개발팀", "TEAM", 11, 105), 3: ("영업팀", "TEAM", 12, 106),
}
EMPLOYEES = {  # emp_id: (이름, 부서, 직급ID, 직책ID)
    101: ("이서연", 1, 7, 1), 102: ("최민준", 1, 23, 2), 103: ("정하은", 2, 2, 1), 104: ("강도윤", 2, 13, 1),
    105: ("윤지호", 2, 24, 2), 106: ("한예린", 3, 15, 2), 107: ("서동현", 10, 27, 3), 108: ("임채원", 11, 28, 3),
    109: ("문태준", 13, 30, 4),
}

tokens = {"TOKEN_104": make_token(104, deptId=2, gradeId=13, dutyId=1),
          "TOKEN_105": make_token(105, deptId=2, gradeId=24, dutyId=2)}
out = Path(__file__).with_name(".tokens.sh")
out.write_text("".join(f"export {k}='{v}'\n" for k, v in tokens.items()), encoding="utf-8")

DOCUMENTS: dict[int, dict] = {}   # 메모리 저장 (서버 끄면 사라짐)
FORM_SOURCES = {"GENERAL": {"DIRECT", "AUTOMATION"}, "BUSINESS_TRIP": {"DIRECT"}}  # 결재 서비스 V10 규칙


def employee_info(emp_id: int) -> dict:
    name, dept, grade, duty = EMPLOYEES[emp_id]
    return {"empId": emp_id, "empNo": f"DEV0{emp_id}", "name": name, "loginId": f"user{emp_id - 100}@local.dev",
            "role": "USER", "deptId": dept, "positionId": None, "gradeId": grade, "dutyId": duty,
            "empType": "REGULAR", "hireType": "ROLL", "hireDate": "2018-09-03", "status": "EMPLOYED"}


def org_chain(emp_id: int) -> dict:
    dept = EMPLOYEES[emp_id][1]
    chain = []
    while dept is not None:
        name, dtype, parent, head = DEPTS[dept]
        chain.append({"deptId": dept, "deptName": name, "deptType": dtype, "headEmpId": head})
        dept = parent
    return {"employeeId": emp_id, "deptId": EMPLOYEES[emp_id][1], "chain": chain}


# ---------------------------------------------------------------- HTTP
class Handler(BaseHTTPRequestHandler):
    def _send(self, status: int, body: dict):
        raw = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def ok(self, data, status=200):
        self._send(status, {"success": True, "error": None, "data": data})

    def fail(self, status, code, message):
        self._send(status, {"success": False, "error": {"code": code, "message": message}, "data": None})

    def auth(self) -> int | None:
        """팀원 서비스처럼 Bearer 토큰 검증 → 사원 ID"""
        header = self.headers.get("Authorization", "")
        if not header.startswith("Bearer "):
            self.fail(401, "AUTH_001", "인증이 필요합니다.")
            return None
        try:
            claims = jwt.decode(header[7:], key.public_key(), algorithms=["RS256"])
        except jwt.PyJWTError as e:
            self.fail(401, "AUTH_002", f"유효하지 않은 토큰: {e}")
            return None
        return int(claims["sub"])

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/.well-known/jwks.json":
            return self._send(200, JWKS)
        if self.auth() is None:
            return
        if m := re.fullmatch(r"/api/v1/employees/(\d+)", path):
            emp = int(m[1])
            return self.ok(employee_info(emp)) if emp in EMPLOYEES else self.fail(404, "USER_001", "사원이 없습니다.")
        if m := re.fullmatch(r"/api/v1/employees/(\d+)/org-chain", path):
            emp = int(m[1])
            return self.ok(org_chain(emp)) if emp in EMPLOYEES else self.fail(404, "USER_001", "사원이 없습니다.")
        if m := re.fullmatch(r"/api/v1/documents/by-request/([0-9a-f-]+)", path):
            doc = next((d for d in DOCUMENTS.values() if d["requestKey"] == m[1]), None)
            return self.ok(doc) if doc else self.fail(404, "DOC_001", "문서가 없습니다.")
        self.fail(404, "COMMON_003", "없는 주소")

    def do_POST(self):
        path = self.path.split("?")[0]
        length = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(length) or b"{}")

        # 테스트용: 결재자 대신 승인/반려 (토큰 없이)
        if m := re.fullmatch(r"/_dev/documents/(\d+)/(approve|reject)", path):
            doc = DOCUMENTS.get(int(m[1]))
            if not doc:
                return self.fail(404, "DOC_001", "문서가 없습니다.")
            doc["status"] = "APPROVED" if m[2] == "approve" else "REJECTED"
            doc["completedAt"] = datetime.now(timezone.utc).isoformat()
            print(f"[결재] 문서 {doc['documentId']} → {doc['status']}")
            return self.ok(doc)

        if (requester := self.auth()) is None:
            return
        if path == "/api/v1/documents":
            # 결재 서비스 validateSource 규칙 흉내
            form, source = body.get("formType"), body.get("sourceType") or "DIRECT"
            direct = source == "DIRECT"
            if direct != (body.get("sourceId") is None):
                return self.fail(400, "DOC_010", "출처와 원본 신청 ID가 맞지 않습니다.")
            if source not in FORM_SOURCES.get(form, set()):
                return self.fail(400, "DOC_011", f"{form} 양식은 {source} 출처로 만들 수 없습니다.")
            if not direct and not body.get("requestKey"):
                return self.fail(400, "DOC_012", "요청 키가 필요합니다.")
            req_key = body.get("requestKey")
            if req_key and (doc := next((d for d in DOCUMENTS.values() if d["requestKey"] == req_key), None)):
                print(f"[결재] 같은 요청 키 → 기존 문서 {doc['documentId']} 반환")
                return self.ok({"documentId": doc["documentId"], "docNo": doc["docNo"]}, 200)
            doc_id = len(DOCUMENTS) + 1
            doc = {"documentId": doc_id, "docNo": f"DOC-{doc_id:04d}", "formType": form, "title": body.get("title"),
                   "status": "INPROGRESS", "drafterId": requester, "draftedAt": datetime.now(timezone.utc).isoformat(),
                   "completedAt": None, "sourceType": source, "sourceId": body.get("sourceId"), "requestKey": req_key,
                   "attributes": body.get("attributes")}
            DOCUMENTS[doc_id] = doc
            print(f"[결재] 상신: {doc_id} '{doc['title']}' 기안자={requester} 출처={source} 금액={(doc['attributes'] or {}).get('amount')}")
            return self.ok({"documentId": doc_id, "docNo": doc["docNo"]}, 201)
        self.fail(404, "COMMON_003", "없는 주소")

    def log_message(self, fmt, *args):
        pass


print(f"토큰 저장: {out.as_posix()}  →  source scripts/.tokens.sh")
print(f"가짜 팀원 서비스: http://127.0.0.1:{PORT}  (Ctrl+C로 종료)")
ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()