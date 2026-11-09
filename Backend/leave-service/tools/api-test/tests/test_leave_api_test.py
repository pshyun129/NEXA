import contextlib
import copy
import io
import json
import re
import tempfile
import threading
import unittest
from collections import Counter
from datetime import datetime, timedelta
from pathlib import Path
from unittest.mock import patch

import leave_api_test as tool
from mock_gateway import GatewayState, make_server


class JourneyTests(unittest.TestCase):
    def setUp(self):
        self.state = GatewayState()
        self.server = make_server(self.state)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.temp = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[1] / "results")
        self.addCleanup(self.temp.cleanup)
        self.output = Path(self.temp.name) / "result.json"
        base = f"http://127.0.0.1:{self.server.server_port}"
        self.settings = {"BASE_URL": base, "INTERNAL_LEAVE_URL": base, "ENVIRONMENT": "local-test", "DATA_KEY": "LEAVE_TEST",
                         "ALLOW_WRITES": "true", "DEDICATED_EMPLOYEE_CONFIRMED": "true", "EXPECTED_EMPLOYEE_ID": "103",
                         "BALANCE_TYPE_ID": "4", "ACCOUNT_ID": "5", "GRANT_ID": "6", "INITIAL_GRANT_ENTRY_ID": "7", "POLL_TIMEOUT": "0.1"}
        for actor in ("admin", "ordinary", "teamlead", "approver"):
            self.settings[actor.upper() + "_EMAIL"] = actor + "@test"
            self.settings[actor.upper() + "_PASS"] = "private-password"

    def run_tool(self):
        self.logs = io.StringIO()
        with contextlib.redirect_stdout(self.logs):
            code = tool.main(self.settings, self.output)
        self.report = json.loads(self.output.read_text(encoding="utf-8"))
        return code

    def test_all_39_and_three_settlements(self):
        self.assertEqual(0, self.run_tool())
        self.assertEqual({"totalApis": 39, "PASS": 39, "FAIL": 0, "SKIP": 0, "BLOCKED": 0}, self.report["summary"])
        self.assertEqual(["PASS"] * 3, [r["outcome"] for r in self.report["integrations"]])
        self.assertEqual(3, len(self.state.requests))
        self.assertEqual(["APPROVED", "REJECTED", "WITHDRAWN"], [r["status"] for r in self.state.requests.values()])
        self.assertEqual((6690, 0), (self.state.balances()[0]["quantityMinutes"], self.state.balances()[0]["reservedMinutes"]))
        self.assertEqual(1, sum(r["eventType"] == "GRANT" for r in self.state.ledger))

    def test_enabled_admin_cancel_restores_balance_and_preserves_approval_document(self):
        self.settings["TEST_ADMIN_CANCEL_ENABLED"] = "true"
        self.state.admin_cancel_enabled = True
        self.assertEqual(0, self.run_tool())
        self.assertEqual("PASS", self.report["apis"]["admin_cancel"]["outcome"])
        scenario = self.report["integrations"][-1]
        self.assertEqual(("admin_cancel", "PASS"), (scenario["scenario"], scenario["outcome"]))
        self.assertEqual("CANCELLED", self.state.requests[scenario["requestId"]]["status"])
        self.assertEqual("APPROVED", self.state.documents[scenario["approvalDocId"]]["status"])
        restores = [entry for entry in self.state.ledger if entry["eventType"] == "RESTORE"]
        self.assertEqual(1, len(restores))
        self.assertEqual((480, 0), (restores[0]["quantityDelta"], restores[0]["reservedDelta"]))
        self.assertEqual(7170, self.state.balances()[0]["quantityMinutes"])
        self.assertEqual(2, sum(call["method"] == "POST" and call["path"].endswith(
            f"/admin/requests/{scenario['requestId']}/cancel") for call in self.state.calls))

    def test_enabled_admin_cancel_blocks_when_backend_feature_is_disabled(self):
        self.settings["TEST_ADMIN_CANCEL_ENABLED"] = "true"
        self.assertNotEqual(0, self.run_tool())
        self.assertNotEqual("PASS", self.report["apis"]["admin_cancel"]["outcome"])
        self.assertEqual(0, sum(entry["eventType"] == "RESTORE" for entry in self.state.ledger))

    def test_enabled_admin_cancel_never_writes_without_dedicated_employee_confirmation(self):
        self.settings["TEST_ADMIN_CANCEL_ENABLED"] = "true"
        self.settings["DEDICATED_EMPLOYEE_CONFIRMED"] = "false"
        self.state.admin_cancel_enabled = True
        self.run_tool()
        self.assertFalse(any(call["method"] == "POST" and "/admin/requests/" in call["path"]
                             and call["path"].endswith("/cancel") for call in self.state.calls))

    def test_api_routes_match_real_controllers(self):
        source = Path(__file__).resolve().parents[3] / "src/main/java"
        actual = set()
        for path in source.rglob("*Controller.java"):
            text = path.read_text(encoding="utf-8")
            base = re.search(r'@RequestMapping\("([^"]+)"\)', text)
            if base:
                for method, suffix in re.findall(r'@(Get|Post|Patch|Put|Delete)Mapping(?:\("([^"]*)"\))?', text):
                    actual.add((method.upper(), base[1] + suffix))
        covered = {(method, tool.PREFIX + path) for method, path in tool.API_ROUTES.values()}
        # 수동 지급 POST는 별도 검증 대상이며 기존 여정에는 아직 포함하지 않는다.
        self.assertEqual(set(), covered - actual)
        self.assertEqual({("POST", "/api/v1/leaves/admin/employees/{employeeId}/grants")}, actual - covered)
        self.assertEqual(Counter(GET=24, POST=8, PATCH=3, PUT=3, DELETE=1), Counter(method for method, _ in tool.API_ROUTES.values()))

    def test_login_uses_email_pass_and_isolated_cookies(self):
        self.run_tool()
        logins = [c for c in self.state.calls if c["path"] == "/api/v1/auth/login"]
        self.assertEqual(4, len(logins))
        self.assertTrue(all(set(c["body"]) == {"email", "pass"} and c["cookie"] is None for c in logins))
        admin_calls = [c for c in self.state.calls if c["path"].startswith("/api/v1/leaves/admin/") and c["actor"] == 900]
        self.assertTrue(admin_calls)
        self.assertTrue(all(c["cookie"] == "accessToken=session-900" for c in admin_calls))
        internal = [c for c in self.state.calls if c["path"].startswith("/api/v1/leaves/internal/")]
        self.assertTrue(internal)
        self.assertTrue(all(c["cookie"] is None for c in internal))

    def test_created_ids_are_used_by_following_requests(self):
        self.run_tool()
        policy = self.report["createdIds"]["policyId"]
        version = self.report["createdIds"]["versionId"]
        paths = [c["path"] for c in self.state.calls]
        self.assertIn(f"/api/v1/leaves/admin/policies/{policy}/versions/{version}/accrual-rules", paths)
        self.assertIn(f"/api/v1/leaves/admin/policies/{policy}/versions/{version}/type-rules/{self.report['createdIds']['leaveTypeId']}", paths)

    def test_existing_assignment_preserved_and_conflict_verified(self):
        before = copy.deepcopy(self.state.assignments)
        self.run_tool()
        self.assertEqual(before, self.state.assignments)
        assignment = self.report["apis"]["assign"]
        self.assertEqual("PASS", assignment["outcome"])
        self.assertEqual(409, assignment["checks"][0]["actualStatus"])

    def test_initial_setup_stops_at_missing_grant_without_inventing_credit(self):
        self.state.types, self.state.policies, self.state.versions, self.state.rules = {}, {}, {}, {}
        self.state.assignments, self.state.grants, self.state.ledger = [], [], []
        for key in ("ACCOUNT_ID", "GRANT_ID", "INITIAL_GRANT_ENTRY_ID"):
            self.settings[key] = ""
        self.assertEqual(2, self.run_tool())
        self.assertEqual(1, len(self.state.assignments))
        self.assertEqual([], self.state.grants)
        self.assertEqual([], self.state.ledger)
        self.assertEqual("PASS", self.report["apis"]["assign"]["outcome"])
        # NONE은 지급이 필요 없으므로 실제 연동 검증 가능, 계정용 ADJUST는 BLOCKED다.
        self.assertEqual("BLOCKED", self.report["apis"]["adjust"]["outcome"])
        self.assertTrue(any("SQL 02" in message for message in self.report["preparation"]))

    def test_repeated_execution_no_double_grant_or_reassignment(self):
        self.run_tool()
        first_name = self.report["runName"]
        before_assignment = copy.deepcopy(self.state.assignments)
        self.run_tool()
        self.assertNotEqual(first_name, self.report["runName"])
        self.assertEqual(1, sum(r["eventType"] == "GRANT" for r in self.state.ledger))
        self.assertEqual(before_assignment, self.state.assignments)
        self.assertEqual(6, len(self.state.requests))

    def test_failure_does_not_stop_independent_reads(self):
        self.state.failures[("POST", "/api/v1/leaves/admin/policies")] = (500, "COMMON_999")
        self.assertEqual(1, self.run_tool())
        self.assertEqual("FAIL", self.report["apis"]["policy_create"]["outcome"])
        self.assertEqual("BLOCKED", self.report["apis"]["version_create"]["outcome"])
        self.assertEqual("PASS", self.report["apis"]["balances"]["outcome"])
        self.assertEqual("PASS", self.report["apis"]["department_requests"]["outcome"])

    def test_writes_disabled_no_leave_or_approval_mutation(self):
        self.settings["ALLOW_WRITES"] = "false"
        self.run_tool()
        writes = [c for c in self.state.calls if c["method"] != "GET" and c["path"] != "/api/v1/auth/login"]
        self.assertEqual([], writes)

    def test_wrong_employee_disables_writes_but_other_reads_continue(self):
        self.settings["EXPECTED_EMPLOYEE_ID"] = "104"
        self.run_tool()
        self.assertEqual("FAIL", self.report["logins"]["ordinary"]["outcome"])
        self.assertFalse(any(c["method"] != "GET" and c["path"] != "/api/v1/auth/login" for c in self.state.calls))
        self.assertEqual("PASS", self.report["apis"]["policies"]["outcome"])

    def test_missing_admin_still_checks_my_balances(self):
        self.settings["ADMIN_PASS"] = ""
        self.run_tool()
        self.assertEqual("PASS", self.report["apis"]["balances"]["outcome"])
        self.assertEqual("BLOCKED", self.report["apis"]["policy_create"]["outcome"])

    def test_substitute_not_silently_ignored(self):
        self.state.substitute = True
        self.run_tool()
        self.assertFalse(self.state.requests)
        self.assertTrue(any("SUBSTITUTE" in m for m in self.report["preparation"]))

    def test_insufficient_balance_uses_none_without_new_grant(self):
        self.state.entry(None, "ADJUST", -7200, 0)
        self.run_tool()
        self.assertEqual(3, len(self.state.requests))
        self.assertTrue(all(self.state.types[r["leaveTypeId"]]["balanceMode"] == "NONE" for r in self.state.requests.values()))
        self.assertEqual(0, self.state.balances()[0]["quantityMinutes"])
        self.assertEqual(1, sum(r["eventType"] == "GRANT" for r in self.state.ledger))

    def test_no_credentials_or_server_error_body_in_result_or_stdout(self):
        self.state.failures[("PATCH", "/api/v1/leaves/admin/types/1001")] = (400, "COMMON_001")
        self.run_tool()
        text = self.output.read_text(encoding="utf-8") + self.logs.getvalue()
        for secret in ("private-password", "session-900", "session-103", "private-server-message", "admin@test", "ordinary@test"):
            self.assertNotIn(secret, text)

    def test_unknown_post_is_not_retried_and_independent_reads_continue(self):
        self.state.drop_path = ("POST", "/api/v1/leaves/admin/policies")
        self.run_tool()
        posts = [c for c in self.state.calls if c["method"] == "POST" and c["path"] == "/api/v1/leaves/admin/policies"]
        self.assertEqual(1, len(posts))
        self.assertEqual("BLOCKED", self.report["apis"]["policy_create"]["outcome"])
        self.assertEqual("PASS", self.report["apis"]["ledger"]["outcome"])

    def test_internal_not_configured_only_that_api_blocked(self):
        self.settings["INTERNAL_LEAVE_URL"] = ""
        self.run_tool()
        self.assertEqual("BLOCKED", self.report["apis"]["internal_dates"]["outcome"])
        self.assertTrue(all(r["outcome"] == "PASS" for r in self.report["integrations"]))

    def test_real_settlement_timeout_not_marked_pass(self):
        self.state.failures[("POST", "/api/v1/documents/1015/approve")] = (500, "COMMON_999")
        # 실제 문서 ID와 무관하게 최종 상태가 오지 않게 한다.
        original = self.state.dispatch
        def suppress(method, path, body, actor):
            if method == "POST" and path.endswith("/approve"):
                return 200, {"success": True, "error": None, "data": None}
            return original(method, path, body, actor)
        self.state.dispatch = suppress
        self.run_tool()
        self.assertEqual("BLOCKED", self.report["integrations"][0]["outcome"])
        self.assertEqual(1, len(self.state.requests))

    def test_env_parser_preserves_password_and_never_executes_it(self):
        path = Path(self.temp.name) / ".env"
        path.write_text("# comment\nPASSWORD='a#b=c$()'\nBASE_URL=https://example.invalid\n", encoding="utf-8")
        values = tool.load_env(path)
        self.assertEqual("a#b=c$()", values["PASSWORD"])

    def test_bad_balance_is_failure_even_when_http_200(self):
        original = self.state.balances
        def bad_balances():
            rows = original()
            rows[0]["remainingMinutes"] += 1
            return rows
        self.state.balances = bad_balances
        self.assertEqual(1, self.run_tool())
        self.assertEqual("FAIL", self.report["apis"]["balances"]["outcome"])
        self.assertEqual("PASS", self.report["apis"]["policies"]["outcome"])

    def test_changed_response_value_fails_even_when_http_201(self):
        original = self.state.dispatch
        def wrong_response(method, path, body, actor):
            status, envelope = original(method, path, body, actor)
            if method == "POST" and path == "/api/v1/leaves/admin/types":
                envelope["data"]["paid"] = False
            return status, envelope
        self.state.dispatch = wrong_response
        self.assertEqual(1, self.run_tool())
        self.assertEqual("FAIL", self.report["apis"]["type_create"]["outcome"])
        self.assertEqual("BLOCKED", self.report["apis"]["type_patch"]["outcome"])

    def test_submission_response_loss_keeps_key_and_does_not_send_next_request(self):
        self.state.drop_path = ("POST", "/api/v1/leaves/me/requests")
        self.run_tool()
        posts = [c for c in self.state.calls if c["method"] == "POST" and c["path"] == "/api/v1/leaves/me/requests"]
        self.assertEqual(1, len(posts))
        self.assertEqual(posts[0]["key"], self.report["integrations"][0]["idempotencyKey"])
        self.assertEqual("BLOCKED", self.report["integrations"][0]["outcome"])
        self.assertEqual("PASS", self.report["apis"]["department_balances"]["outcome"])

    def test_pending_existing_request_blocks_employee_mutations(self):
        self.state.requests[88] = {"requestId": 88, "employeeId": 103, "status": "SUBMITTING", "dates": []}
        self.run_tool()
        self.assertFalse(any(c["method"] == "POST" and c["path"].endswith(("/adjustments", "/policy-assignments")) for c in self.state.calls))
        self.assertEqual(1, len(self.state.requests))
        self.assertEqual("PASS", self.report["apis"]["policies"]["outcome"])

    def test_existing_approved_utc_date_is_excluded_using_seoul_calendar(self):
        day = datetime.now(tool.SEOUL).date() + timedelta(days=1)
        while day.weekday() >= 5:
            day += timedelta(days=1)
        self.state.requests[88] = {"requestId": 88, "employeeId": 103, "leaveTypeId": 1,
                                   "status": "APPROVED", "dates": [{"leaveDate": str(day), "portion": "FULL"}]}
        self.state.entry(88, "RESERVE", 0, 480)
        self.state.entry(88, "CONSUME", -480, -480)
        self.assertEqual(0, self.run_tool())
        bodies = [c["body"] for c in self.state.calls if c["method"] == "POST" and c["path"] == "/api/v1/leaves/me/requests"]
        self.assertEqual(3, len(bodies))
        self.assertTrue(all(item["leaveDate"] != str(day) for body in bodies for item in body["dates"]))

    def test_missing_base_url_never_calls_server_and_writes_report(self):
        self.settings["BASE_URL"] = ""
        self.assertEqual(2, self.run_tool())
        self.assertEqual([], self.state.calls)
        self.assertEqual(39, self.report["summary"]["BLOCKED"])

    def test_malformed_env_does_not_leak_exception_or_reuse_old_success(self):
        with patch.object(tool, "load_env", side_effect=ValueError("private-password")):
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(2, tool.main(output=self.output))
        report = json.loads(self.output.read_text(encoding="utf-8"))
        self.assertEqual(39, report["summary"]["BLOCKED"])
        self.assertNotIn("private-password", self.output.read_text(encoding="utf-8"))


class DateContractTests(unittest.TestCase):
    def test_utc_previous_day_is_the_same_seoul_leave_day(self):
        self.assertEqual("2026-11-09", tool.request_date_in_seoul("2026-11-08T15:00:00Z"))

    def test_explicit_seoul_offset_is_preserved(self):
        self.assertEqual("2026-11-09", tool.request_date_in_seoul("2026-11-09T00:00:00+09:00"))

    def test_timezone_missing_is_not_silently_assumed(self):
        for value in ("2026-11-09", "2026-11-09T00:00:00"):
            with self.subTest(value=value), self.assertRaises(AssertionError):
                tool.request_date_in_seoul(value)

    def test_actual_detail_dto_uses_offset_datetime(self):
        source = Path(__file__).resolve().parents[3] / "src/main/java/com/ssafy/leaveservice/domain/request/dto/LeaveRequestDateResponse.java"
        self.assertIn("OffsetDateTime leaveDate", source.read_text(encoding="utf-8"))


class ResultStorageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[1] / "results")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def test_new_execution_creates_independent_numbered_file(self):
        original = self.root / "result.json"
        original.write_text("existing", encoding="utf-8")
        with patch.object(tool, "ROOT", self.root), contextlib.redirect_stdout(io.StringIO()):
            tool.main(settings={})
            first = self.root / "result/result_ver1.json"
            contents = first.read_text(encoding="utf-8")
            tool.main(settings={})
        self.assertEqual(contents, first.read_text(encoding="utf-8"))
        self.assertTrue((self.root / "result/result_ver2.json").is_file())
        self.assertEqual("existing", original.read_text(encoding="utf-8"))

    def test_uses_maximum_version_without_filling_old_gaps(self):
        directory = self.root / "result"
        directory.mkdir()
        (directory / "result_ver1.json").write_text("one", encoding="utf-8")
        (directory / "result_ver3.json").write_text("three", encoding="utf-8")
        (directory / "notes.json").write_text("ignore", encoding="utf-8")
        with patch.object(tool, "ROOT", self.root):
            self.assertEqual(directory / "result_ver4.json", tool.new_result_path())


if __name__ == "__main__":
    unittest.main()
