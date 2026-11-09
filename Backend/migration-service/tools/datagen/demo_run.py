"""
시연 리허설: demo/ 파일로 이관 API를 시연 순서대로 끝까지 돌리고 단계마다 숫자를 찍는다 (demo/README.md).
화면 없이 서버만 확인할 때 쓴다. 표준 라이브러리만 쓴다.

사용: python -I demo_run.py [--base http://localhost:8087] [--token <관리자 access token>] [--rollback]
  --rollback: 끝나고 급여명세서 → 직급별 기본급 → 사원 → 조직 → 기준정보 순으로 되돌린다 (리허설을 반복할 때)
"""
import argparse
import collections
import json
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path

DEMO = Path(__file__).resolve().parent / "demo"


class Api:
    def __init__(self, base, token):
        self.base = base.rstrip("/") + "/api/v1/admin/migrations"
        self.auth = {"Authorization": "Bearer " + token} if token else {}

    def call(self, method, path, body=None, headers=None):
        if isinstance(body, (dict, list)):
            body, headers = json.dumps(body, ensure_ascii=False).encode(), {"Content-Type": "application/json"}
        req = urllib.request.Request(self.base + path, data=body, method=method, headers={**self.auth, **(headers or {})})
        try:
            with urllib.request.urlopen(req, timeout=600) as r:
                return json.loads(r.read().decode("utf-8"))["data"]
        except urllib.error.HTTPError as e:
            sys.exit("%s %s → %d %s" % (method, path, e.code, e.read().decode("utf-8")[:500]))

    def upload(self, mid, path, entity, sheet=None):
        b = uuid.uuid4().hex
        parts = [("entity", entity)] + ([("sheet", sheet)] if sheet else [])
        body = "".join('--%s\r\nContent-Disposition: form-data; name="%s"\r\n\r\n%s\r\n' % (b, k, v) for k, v in parts).encode()
        body += ('--%s\r\nContent-Disposition: form-data; name="file"; filename="%s"\r\n'
                 "Content-Type: application/octet-stream\r\n\r\n" % (b, path.name)).encode()
        body += path.read_bytes() + ("\r\n--%s--\r\n" % b).encode()
        return self.call("POST", "/%d/files" % mid, body, {"Content-Type": "multipart/form-data; boundary=" + b})


def show_groups(api, mid, fid):
    for g in api.call("GET", "/%d/issues?fileId=%d&size=1" % (mid, fid))["groups"]:
        print("    %-5s %-26s %5d건  예: %s행" % (g["severity"], g["code"], g["count"], g.get("sampleRows")))


def confirm_mapping(api, mid, fid):
    """화면에서 추천 매핑을 그대로 확정하는 것과 같다. 필드 → 원본 열"""
    maps = api.call("GET", "/%d/files/%d" % (mid, fid))["columnMappings"]
    api.call("PUT", "/%d/files/%d/mappings" % (mid, fid),
             {"columnMappings": [{"sourceColumn": m["sourceColumn"], "targetField": m["targetField"]} for m in maps]})
    return {m["targetField"]: m["sourceColumn"] for m in maps if m["targetField"]}


def batches(res):
    return ", ".join("%s %s (원본 %d, 생성 %d, 기존 %d)" % (b["entity"], b["status"], b["sourceRows"], b["created"], b["updated"])
                     for b in res["batches"])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8087")
    ap.add_argument("--token")
    ap.add_argument("--rollback", action="store_true")
    args = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    api = Api(args.base, args.token)

    mid = api.call("POST", "", {"companyId": 1, "name": "시연 리허설", "cutoverDate": "2026-12-01"})["migrationId"]
    print("작업 %d" % mid)

    print("\n① 조직.xlsx (HR_ORG)")
    org = api.upload(mid, DEMO / "조직.xlsx", "HR_ORG")
    confirm_mapping(api, mid, org["fileId"])
    print("  %d행, 이슈 %s" % (org["rows"], org["issueTotals"]))
    print("  적재: " + batches(api.call("POST", "/%d/loads" % mid, {"fileIds": [org["fileId"]]})))

    print("\n② 사원.xlsx (HR_EMPLOYEE)")
    emp = api.upload(mid, DEMO / "사원.xlsx", "HR_EMPLOYEE")
    cols = confirm_mapping(api, mid, emp["fileId"])
    print("  %d행, 이슈 %s" % (emp["rows"], emp["issueTotals"]))
    show_groups(api, mid, emp["fileId"])
    fx = api.call("POST", "/%d/fixes" % mid)
    print("  [자동 정리 적용] 바뀐 셀 %d → %s" % (fx["applied"], fx["issueTotals"]))
    for b in api.call("GET", "/%d/issues?fileId=%d&severity=BLOCK&size=10" % (mid, emp["fileId"]))["content"]:
        ru = api.call("PATCH", "/%d/files/%d/rows/%d" % (mid, emp["fileId"], b["rowNo"]), {"values": {cols[b["field"]]: "2019-03-04"}})
        print("  [행 수정] %d행 %s → 남은 오류 %d, %s" % (b["rowNo"], b["message"], len(ru["rowIssues"]), ru["issueTotals"]))
    refs = api.call("GET", "/%d/references" % mid)["items"]
    print("  기준정보: " + ", ".join("%s %s %d" % (t, s, n) for (t, s), n in collections.Counter((i["type"], i["status"]) for i in refs).items()))
    pv = api.call("POST", "/%d/loads?dryRun=true" % mid, {"fileIds": [emp["fileId"]]})
    print("  미리보기: 적재 가능 %s, %s" % (pv["loadable"], ", ".join("%s %d행" % (f["entity"], f["rows"]) for f in pv["files"])))
    loaded = api.call("POST", "/%d/loads" % mid, {"fileIds": [emp["fileId"]]})
    print("  적재: " + batches(loaded))

    print("\n③ 급여대장_2026-11.xlsx (PAY_RESULT_HISTORY, 시트 2026-11)")
    pay = api.upload(mid, DEMO / "급여대장_2026-11.xlsx", "PAY_RESULT_HISTORY", "2026-11")
    pay_cols = confirm_mapping(api, mid, pay["fileId"])
    print("  %d행, 이슈 %s" % (pay["rows"], pay["issueTotals"]))
    show_groups(api, mid, pay["fileId"])
    for b in api.call("GET", "/%d/issues?fileId=%d&severity=BLOCK&size=10" % (mid, pay["fileId"]))["content"]:
        fixed = b["value"].replace("O", "0")  # 사번 오타 (숫자 0 대신 영문 O)
        ru = api.call("PATCH", "/%d/files/%d/rows/%d" % (mid, pay["fileId"], b["rowNo"]), {"values": {pay_cols["empNo"]: fixed}})
        print("  [행 수정] %d행 사번 %s → %s, 남은 오류 %d, %s" % (b["rowNo"], b["value"], fixed, len(ru["rowIssues"]), ru["issueTotals"]))
    loaded = api.call("POST", "/%d/loads" % mid, {"fileIds": [pay["fileId"]]})
    print("  적재: " + batches(loaded))

    print("\n④ 대사")
    for it in api.call("GET", "/%d/reconciliation" % mid):
        print("  %-18s 원본 %5d = 적재 %5d %s" % (it["entity"], it["source"], it["loaded"], "✓" if it["match"] else "✗"))

    if args.rollback:
        print("\n⑤ 되돌리기")
        order = ["PAY_RESULT_HISTORY", "PAY_PROFILE", "HR_EMPLOYEE", "HR_ORG", "HR_CODE"]
        for b in sorted(loaded["batches"], key=lambda b: order.index(b["entity"])):
            r = api.call("DELETE", "/%d/batches/%s" % (mid, b["batchId"]))
            print("  %s 되돌림 → 작업 %s" % (b["entity"], r["status"]))


if __name__ == "__main__":
    main()
