"""
가상 회사 데이터를 이관 서비스에 올리고 정답지(answer_key.json)와 비교한다.

- 직원·조직: 추천 매핑 == 정답 매핑, 정답지 오류 (rowNo, field, code) 중 서비스가 찾은 비율, 오탐
- 인사 엑셀(급여대장 등): 파일의 데이터 시트마다 따로 올린다 (sheet 지정). 가장 가까운 entity가 없으면 REFERENCE(분석 전용).
  - 읽기: 서비스가 읽은 행 수·헤더가 정답지 시트 구조와 맞는지
  - 사고: 정답지 사고 유형 → 서비스 이슈 코드 대응표(ACCIDENT_CODES)로 (시트, 행) 위치가 맞는 이슈가 있으면 검출
  - 대응표에 있는 코드인데 정답지 사고 위치가 아닌 이슈는 '정답지에 없는 경고'로 따로 센다

생성기와 서비스 검증 규칙을 같은 쪽이 만들었으므로 검출률은 회귀 확인일 뿐이다.

사용: python -I evaluate.py <out 폴더> [--base http://localhost:8087]
"""
import argparse
import json
import re
import urllib.error
import urllib.parse
import urllib.request
import uuid
from collections import Counter, defaultdict
from pathlib import Path

API = "/api/v1/admin/migrations"

# 직원명부 오류 코드 → 서비스가 같은 오류를 낼 때 쓰는 코드 (이름이 다른 것만)
ROSTER_CODE_ALIAS = {"POSITION_TYPO": {"CODE_TYPO_SUSPECTED"}, "INVALID_ENUM": {"ENUM_VALUE_SUGGESTED"}}  # 허용 값 밖 값은 LLM 추천이 있으면 정리 제안으로 나옴

# 인사 엑셀 사고 유형 → 이 사고를 잡았다고 볼 서비스 이슈 코드
ACCIDENT_CODES = {
    "HARDCODED_EQUAL": {"HARDCODED_VALUE"}, "HARDCODED_STALE": {"HARDCODED_VALUE", "PREMIUM_BELOW_STATUTORY"},
    "REMAIN_HARDCODED": {"HARDCODED_VALUE", "RECALC_MISMATCH"}, "GRANTED_HARDCODED": {"HARDCODED_VALUE"},
    "AMOUNT_HARDCODED": {"HARDCODED_VALUE", "SALARY_PAYROLL_MISMATCH"}, "RATE_AMOUNT_MISMATCH": {"HARDCODED_VALUE"},
    "NEW_ROW_FORMULA_MISSING": {"BLANK_IN_FORMULA_COLUMN"},
    "ROW_AFTER_TOTAL": {"ROW_AFTER_TOTAL", "HARDCODED_VALUE"},  # 합계 아래 행은 계산 결과도 숫자로만 입력
    "REF_ERROR": {"FORMULA_ERROR", "FORMULA_INCONSISTENT"}, "LOOKUP_NA": {"FORMULA_ERROR"},
    "SUM_RANGE_MISSING": {"FORMULA_INCONSISTENT", "FORMULA_DIFFERS_ACROSS_SHEETS", "RECALC_MISMATCH", "TOTAL_ITEM_MISSING"},
    "STALE_RATE_LITERAL": {"FORMULA_INCONSISTENT", "FORMULA_DIFFERS_ACROSS_SHEETS", "INSURANCE_RATE_OUTDATED"},
    "BASE_DATE_WRONG": {"FORMULA_INCONSISTENT"}, "UNDER_ONE_YEAR_PAID": {"FORMULA_INCONSISTENT"},
    "KEY_MISSING": {"EMP_KEY_MISSING"},
    # 직원명부 날짜 대조
    "ACQ_DATE_MISMATCH": {"INSURANCE_ACQ_DATE_MISMATCH"}, "LOSS_DATE_RULE": {"INSURANCE_LOSS_DATE_MISMATCH"},
    "LOSS_MISSING": {"INSURANCE_LOSS_DATE_MISMATCH"}, "HIRE_DATE_MISMATCH": {"ROSTER_HIRE_DATE_MISMATCH"},
    # 부양가족
    "DEDUCTION_AGE_RULE": {"DEPENDENT_AGE_RULE"}, "RATIO_INVALID": {"WITHHOLDING_RATIO_INVALID"},
    "FAMILY_COUNT_MISMATCH": {"FAMILY_COUNT_MISMATCH"},
    # 연봉이력
    "SALARY_NOT_UPDATED": {"SALARY_PAYROLL_MISMATCH"}, "VALUE_MISSING": {"SALARY_PAYROLL_MISMATCH"},
    "PERCENT_AS_NUMBER": {"RAISE_RATE_AS_PERCENT"}, "WAGE_NOT_UPDATED": {"SALARY_HISTORY_MISMATCH"},
    # 추가 회사(newco) 사고 중 기존 서비스 코드와 뜻이 같은 것. 대응이 없는 유형은 비워 둔다
    "TOTAL_MISMATCH": {"RECALC_MISMATCH"}, "MIN_WAGE_VIOLATION": {"BELOW_MINIMUM_WAGE"}, "MIN_WAGE_STALE": {"BELOW_MINIMUM_WAGE"},
    "HOLIDAY_PREMIUM_MISSING": {"PREMIUM_BELOW_STATUTORY"}, "ROW_OUTSIDE_SUBTOTAL": {"ROW_AFTER_TOTAL"},
    "DIVISOR_INCONSISTENT": {"FORMULA_DIFFERS_ACROSS_SHEETS", "FORMULA_INCONSISTENT"},
    "STALE_PAY_TABLE": {"FORMULA_DIFFERS_ACROSS_SHEETS", "FORMULA_INCONSISTENT"},
    "WRONG_YEAR_SHEET": {"FORMULA_DIFFERS_ACROSS_SHEETS"},
    "ALLOWANCE_RULE_CONFLICT": {"HARDCODED_VALUE"}, "INSURANCE_EXEMPTION_WRONG": {"HARDCODED_VALUE"},
    "MIDNIGHT_NOT_HANDLED": {"FORMULA_INCONSISTENT"},
    "RESIGNED_STILL_PAID": {"PAID_OUTSIDE_EMPLOYMENT"}, "NEGATIVE_NET_PAY": {"NEGATIVE_NET_PAY"}, "MONTH_ROW_MISSING": {"PAY_MONTH_GAP"},
    # 2차 회사 사고 중 설명이 기존 코드와 같은 것 ('수식 대신 값 입력', '이 행만 수식이 다름', '시트 수식에 숫자', 수식 오류)
    "DIV_ZERO_ERROR": {"FORMULA_ERROR"}, "CAP_EXCEEDED": {"HARDCODED_VALUE"}, "NIGHT_PREMIUM_MISSING": {"HARDCODED_VALUE"},
    "ORDINARY_WAGE_ITEM_MISSING": {"FORMULA_INCONSISTENT"}, "NONTAX_OVER_LIMIT": {"FORMULA_INCONSISTENT"},
    "SENIORITY_NOT_CARRIED": {"FORMULA_INCONSISTENT"}, "STALE_TAX_THRESHOLD": {"FORMULA_DIFFERS_ACROSS_SHEETS"},
    # 파일 사이 집계 대조·같은 날 두 시트
    "HOURS_MISMATCH_CROSS_FILE": {"CROSS_FILE_MISMATCH"}, "DAYS_MISMATCH_CROSS_FILE": {"CROSS_FILE_MISMATCH"},
    "AMOUNT_MISMATCH_CROSS_FILE": {"CROSS_FILE_MISMATCH"}, "CARRIED_FROM_SOURCE": {"CROSS_FILE_MISMATCH"},
    "REPORT_MISSING": {"MISSING_IN_COPY"}, "SAME_DAY_TWO_SITES": {"SAME_DAY_TWO_SHEETS"}, "CROSS_SHEET_DUPLICATE": {"SAME_DAY_TWO_SHEETS"},
    "KIND_TYPO": {"RECALC_MISMATCH"},  # 근무 코드에 붙은 공백 → COUNTIF 저장값 ≠ 다시 센 값
    # 근무표·체류기간
    "SHIFT_OVERLAP": {"SHIFT_OVERLAP"}, "REST_INTERVAL_SHORT": {"REST_INTERVAL_SHORT"}, "NO_WEEKLY_REST": {"CONSECUTIVE_WORK_DAYS"},
    "VISA_EXPIRED_STILL_PAID": {"VISA_EXPIRED_PAID"},
    "PROBATION_REDUCTION_ILLEGAL": {"BELOW_MINIMUM_WAGE"},  # 단순노무 수습 감액 = 최저임금 미달
}
# 정답지 legalDeviations 규칙 → 이 편차를 잡았다고 볼 서비스 이슈 코드 (시트 단위)
LEGAL_CODES = {
    "insuranceRates": {"INSURANCE_RATE_OUTDATED"},
    "mealInInsuranceBase": {"INSURANCE_BASE_INCLUDES_MEAL"},
    "holidayPremium": {"PREMIUM_BELOW_STATUTORY"},
}
# 직원명부 대조 이슈: 정답지 사고가 아니라 직원명부에 넣은 오류(사번 중복·같은 사람 두 번)의 결과라 따로 센다
REF_CODES = {"EMP_NOT_IN_ROSTER", "EMP_NAME_MISMATCH", "EMPNO_ZERO_PADDED"}
NEW_CODES = set().union(*ACCIDENT_CODES.values(), *LEGAL_CODES.values(), {"BELOW_MINIMUM_WAGE"})  # 최저임금은 정답지에 사례가 없어 오탐만 센다
# 사번 기준으로 보는 사고: 정답지 위치(명세 행)와 다른 시트(집계 행)에 같은 직원 경고가 나와도 검출로 본다
EMP_SCOPED = {"FAMILY_COUNT_MISMATCH": {"FAMILY_COUNT_MISMATCH"}, "AMOUNT_HARDCODED": {"SALARY_PAYROLL_MISMATCH"}}
# 이 사고 때문에 같은 직원의 다른 행에 생기는 경고 (정답지에 따로 없지만 설명됨)
EXPLAINS = {"DEDUCTION_AGE_RULE": {"FAMILY_COUNT_MISMATCH"}, "PERCENT_AS_NUMBER": {"SALARY_PAYROLL_MISMATCH"}}
# 이 사고 때문에 다른 파일의 같은 직원 행에 생기는 경고 (연봉이력이 틀리면 4대보험 연봉(2026)과도 달라진다)
CROSS_EXPLAINS = {t: {"SALARY_HISTORY_MISMATCH"} for t in ("SALARY_NOT_UPDATED", "AMOUNT_HARDCODED", "PERCENT_AS_NUMBER")}
# 두 파일 다 값만 있으면 집계 대조 경고가 상대 파일의 같은 직원 행에도 나온다
CROSS_EXPLAINS |= {t: {"CROSS_FILE_MISMATCH"} for t in ("HOURS_MISMATCH_CROSS_FILE", "DAYS_MISMATCH_CROSS_FILE",
                                                         "AMOUNT_MISMATCH_CROSS_FILE", "CARRIED_FROM_SOURCE")}
SKIP_ROLES = {"settings", "lookupTable"}  # 설정·세액표 시트는 데이터가 아니라서 올리지 않음


def call(base, path, data=None, ctype="application/json", method=None):
    req = urllib.request.Request(base + path, data=data, method=method,
                                 headers={"Content-Type": ctype} if data is not None else {})
    try:
        body = json.load(urllib.request.urlopen(req, timeout=120))
    except urllib.error.HTTPError as e:
        body = json.load(e)
    if not body.get("success"):
        raise RuntimeError(f"{path}: {body.get('error')}")
    return body["data"]


def upload(base, mid, entity, path, sheet=None):
    boundary = "----" + uuid.uuid4().hex
    head = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{path.name}"\r\n'
            f"Content-Type: application/octet-stream\r\n\r\n").encode("utf-8")
    body = head + path.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
    q = f"entity={entity}" + (f"&sheet={urllib.parse.quote(sheet)}" if sheet else "")
    return call(base, f"{API}/{mid}/files?{q}", body, f"multipart/form-data; boundary={boundary}")


def all_issues(base, mid, file_id):
    issues, page = [], 0
    while True:
        d = call(base, f"{API}/{mid}/issues?fileId={file_id}&page={page}&size=500")
        issues += d["content"]
        if (page + 1) * 500 >= d["totalElements"]:
            return issues
        page += 1


def evaluate_company(base, cdir):
    key = json.loads((cdir / "answer_key.json").read_text(encoding="utf-8"))
    mid = call(base, API, json.dumps({"companyId": 1, "name": key["company"] + " 평가"}).encode("utf-8"))["migrationId"]

    result = {"company": key["company"], "mapping": {}, "detection": {}, "falsePositives": Counter(), "fixSuggestions": Counter(), "fileLevel": []}
    found_by_entity = {}
    fix_codes = set()
    for entity, fname in key["files"].items():
        up = upload(base, mid, entity, cdir / fname)
        detail = call(base, f"{API}/{mid}/files/{up['fileId']}")
        expected = key["expectedMapping"][entity]
        got = {m["sourceColumn"]: m["targetField"] for m in detail["columnMappings"]}
        wrong = {c: {"expected": expected[c], "got": got.get(c)} for c in expected if expected[c] != got.get(c)}
        result["mapping"][entity] = {"correct": len(expected) - len(wrong), "total": len(expected), "wrong": wrong}
        issues = all_issues(base, mid, up["fileId"])
        result["fileLevel"] += [i["message"] for i in issues if i["rowNo"] is None]
        found_by_entity[entity] = {(i["rowNo"], i["field"], i["code"]) for i in issues if i["rowNo"] is not None}
        fix_codes |= {i["code"] for i in issues if i.get("severity") == "FIX"}

    found = found_by_entity["HR_EMPLOYEE"]
    expected_errors = {(e["rowNo"], e["field"], e["code"]): e for e in key["errors"]}
    matched = set()
    by_code = defaultdict(lambda: {"expected": 0, "found": 0, "detectable": True})
    for (row_no, field, code), e in expected_errors.items():
        s = by_code[code]
        s["expected"] += 1
        s["detectable"] = e["detectable"]
        hits = {(row_no, field, c) for c in {code} | ROSTER_CODE_ALIAS.get(code, set())} & found
        s["found"] += bool(hits)
        matched |= hits
    result["detection"] = dict(by_code)
    for row_no, field, code in found - matched:   # 정답지에 없는 FIX(자동 정리 제안)는 오탐과 따로 센다
        result["fixSuggestions" if code in fix_codes else "falsePositives"][code] += 1
    result["falsePositives"] = dict(result["falsePositives"])
    result["fixSuggestions"] = dict(result["fixSuggestions"])
    result["orgIssues"] = len(found_by_entity["HR_ORG"])
    cross = {(code, _emp_key(a.get("empNo"))) for hk in key.get("hrFiles", {}).values() for a in hk["accidents"]
             for code in CROSS_EXPLAINS.get(a["type"], set())}
    side = {loc for fname, hk in key.get("hrFiles", {}).items() for a in hk["accidents"] for loc in _side_effects(fname, a)}
    # 파일 사이 대조는 나중에 올린 파일이 앞 파일을 다시 검증하므로, 다 올린 뒤에 이슈를 본다
    uploads = {fname: upload_hr_file(base, mid, cdir, fname, hk) for fname, hk in key.get("hrFiles", {}).items()}
    result["hrFiles"] = [evaluate_hr_file(base, mid, cdir, fname, hk, *uploads[fname], cross, side)
                         for fname, hk in key.get("hrFiles", {}).items()]
    result["reference"] = reference_check(cdir, key, [i for f in result["hrFiles"] for i in f.pop("refIssues")])
    return result


def reference_check(cdir, key, issues):
    """직원명부 대조 이슈가 직원명부에 넣은 오류로 설명되는지"""
    roster = read_roster(cdir, key)
    present = {emp for emp, _ in roster.values()}
    truth = set().union(*[set(h.get("finalState", {})) for h in key["hrFiles"].values()])
    missing = truth - present  # 사번 중복 오류로 명부에서 사라진 사번
    dup_names = {roster[e["rowNo"]][1] for e in key["errors"] if e["code"] == "DUPLICATE_SUSPECTED" and e["rowNo"] in roster}
    out = {"missingFromRoster": sorted(missing), "byCode": {}}
    for code in sorted(REF_CODES):
        mine = [i for i in issues if i["code"] == code]
        if code == "EMP_NOT_IN_ROSTER":
            ok = [i for i in mine if str(i["value"]).zfill(5) in missing]
        elif code == "EMP_NAME_MISMATCH":
            ok = [i for i in mine if i["suggestion"] in dup_names]
        else:
            ok = mine
        out["byCode"][code] = {"issues": len(mine), "explainedByRosterErrors": len(ok), "sheets": len({i["sheet"] for i in mine})}
    return out


def read_roster(cdir, key):
    """직원명부 행 번호 → (사번, 이름). 정답 매핑으로 헤더를 찾는다"""
    path = cdir / key["files"]["HR_EMPLOYEE"]
    mapping = key["expectedMapping"]["HR_EMPLOYEE"]
    emp_h = next(h for h, f in mapping.items() if f == "empNo")
    name_h = next(h for h, f in mapping.items() if f == "name")
    if path.suffix == ".csv":
        import csv
        raw = path.read_bytes()
        try:
            text = raw.decode("utf-8-sig")
        except UnicodeDecodeError:
            text = raw.decode("cp949")
        rows = list(csv.reader(text.splitlines()))
    else:
        import openpyxl
        rows = [[("" if v is None else str(v)) for v in r] for r in openpyxl.load_workbook(path, data_only=True).active.iter_rows(values_only=True)]
    h = next(i for i, r in enumerate(rows) if emp_h in r)
    ei, ni = rows[h].index(emp_h), rows[h].index(name_h)
    return {i + 1: (r[ei].strip(), r[ni].strip()) for i, r in enumerate(rows) if i > h and len(r) > max(ei, ni) and r[ei]}


def upload_hr_file(base, mid, cdir, fname, hk):
    """데이터 시트마다 올리고 읽기 결과를 본다. 업로드가 거절돼도 다른 평가는 계속"""
    entity = hk["closestEntity"] or "REFERENCE"
    res = {"file": fname, "entity": entity, "sheets": [], "accidents": [], "unexpected": Counter(),
           "legalDeviations": len(hk.get("legalDeviations", [])),
           "needs": dict(Counter(n for a in hk["accidents"] for n in a["needs"]))}
    uploaded = []
    for name, meta in hk["sheets"].items():
        if meta.get("role") in SKIP_ROLES:
            continue
        sr = {"sheet": name, "role": meta.get("role")}
        try:
            up = upload(base, mid, entity, cdir / fname, None if fname.lower().endswith(".csv") else name)
            sr["read"] = _read_check(up, meta, hk)
            uploaded.append((sr, up["fileId"]))
        except Exception as e:  # noqa: BLE001 - 업로드 거절도 결과로 남긴다
            sr["error"] = str(e)
        res["sheets"].append(sr)
    return res, uploaded


def evaluate_hr_file(base, mid, cdir, fname, hk, res, uploaded, cross=frozenset(), side=frozenset()):
    """올린 시트들의 이슈로 사고 검출·정답지 밖 경고를 본다"""
    issues_by_sheet = {}
    res["refIssues"] = []
    for sr, file_id in uploaded:
        issues = all_issues(base, mid, file_id)
        issues_by_sheet[sr["sheet"]] = issues
        sr["fileIssues"] = sorted({i["code"] for i in issues if i["rowNo"] is None})
        res["refIssues"] += [{**i, "sheet": sr["sheet"]} for i in issues if i["code"] in REF_CODES]

    explained = set()
    for a in hk["accidents"]:
        codes = ACCIDENT_CODES.get(a["type"])
        item = {"id": a["id"], "type": a["type"], "codes": sorted(codes) if codes else None, "detected": False}
        if codes:
            for sheet, row in _locations(a):
                for i in issues_by_sheet.get(sheet, []):
                    # 시트 단위 이슈(행 없음, 다른 시트와 수식이 다름 등)는 그 시트의 행 사고와도 맞는 것으로 본다
                    if i["code"] in codes and (row is None or i["rowNo"] in (row, None)):
                        item["detected"] = True
                        explained.add((sheet, i["rowNo"], i["code"]))
        if codes and a.get("formula") and a.get("sheet") in issues_by_sheet:
            # 수식 자체를 바꾼 사고: 정답지가 일부 행만 적어도 그 시트에서 같은 코드 경고는 이 사고로 설명된다
            for i in issues_by_sheet[a["sheet"]]:
                if i["code"] in codes:
                    explained.add((a["sheet"], i["rowNo"], i["code"]))
        emp = _emp_key(a.get("empNo"))
        for code_set, detects in ((EMP_SCOPED.get(a["type"], set()), True), (EXPLAINS.get(a["type"], set()), False)):
            for sheet, issues in issues_by_sheet.items() if emp and code_set else []:
                rows = _emp_rows(cdir / fname, sheet, hk)
                for i in issues:
                    if i["code"] in code_set and rows.get(i["rowNo"]) == emp:
                        item["detected"] |= detects
                        explained.add((sheet, i["rowNo"], i["code"]))
        res["accidents"].append(item)
    res["legal"] = []
    for d in hk.get("legalDeviations", []):
        codes = LEGAL_CODES.get(d["rule"]) if "급여" in fname else None   # 다른 파일의 같은 규칙은 파일 사이 대조가 필요
        item = {"rule": d["rule"], "sheet": d.get("sheet") or d.get("cells"), "codes": sorted(codes) if codes else None, "detected": False}
        # 시트를 적은 편차는 그 시트, '열 전체'처럼 시트가 없으면 올린 시트 전부
        sheets = [item["sheet"]] if item["sheet"] in issues_by_sheet else list(issues_by_sheet)
        for sheet in sheets if codes else []:
            for i in issues_by_sheet[sheet]:
                if i["code"] in codes and i["rowNo"] is None:
                    item["detected"] = True
                    explained.add((sheet, None, i["code"]))
        res["legal"].append(item)
    for sheet, issues in issues_by_sheet.items():
        for i in issues:
            if i["code"] in NEW_CODES and i.get("severity") != "FIX" and (sheet, i["rowNo"], i["code"]) not in explained \
                    and (fname, sheet, i["rowNo"]) not in side \
                    and (i["code"], _emp_rows(cdir / fname, sheet, hk).get(i["rowNo"])) not in cross:
                res["unexpected"][i["code"]] += 1
    res["unexpected"] = dict(res["unexpected"])
    return res


_EMP_ROWS = {}


def _emp_key(v):
    s = "" if v is None else str(v).strip()
    return s.lstrip("0") or s if s.isdigit() else s


def _emp_rows(path, sheet, hk):
    """시트의 행 번호 → 사번 (정답 매핑에서 사번 헤더를 찾는다)"""
    if (path, sheet) not in _EMP_ROWS:
        import openpyxl
        meta = hk["sheets"][sheet]
        mapping = hk.get("expectedMapping", {}).get(meta.get("role"), {})
        emp_h = next((h for h, f in mapping.items() if f == "empNo"), None)
        if path.suffix.lower() == ".csv":
            import csv
            raw = path.read_bytes()
            try:
                text = raw.decode("utf-8-sig")
            except UnicodeDecodeError:
                text = raw.decode("cp949")
            rows = [tuple(c or None for c in r) for r in csv.reader(text.splitlines())]
        else:
            rows = list(openpyxl.load_workbook(path, read_only=True, data_only=True)[sheet].iter_rows(values_only=True))
        h = meta["headerRows"][-1] - 1
        col = list(rows[h]).index(emp_h) if emp_h in rows[h] else None
        _EMP_ROWS[(path, sheet)] = {} if col is None else {
            n + 1: _emp_key(r[col]) for n, r in enumerate(rows) if n > h and len(r) > col and r[col] is not None}
    return _EMP_ROWS[(path, sheet)]


_CELL = re.compile(r"(?:(.+)!)?\$?[A-Z]{1,3}\$?(\d+)")


def _side_effects(fname, a):
    """사고 때문에 값이 같이 바뀐 (파일, 시트, 행): 같은 행의 연쇄 열, 소계·집계 칸, 겹친 사고, 다른 파일.
    여기 나온 경고는 사고 검출로 치지 않지만 정답지 밖 경고에서도 뺀다"""
    out = set()
    if a.get("cascade"):
        out |= {(fname, s, r) for s, r in _locations(a) if r is not None}
    cells = [*a.get("cascadeCells", []), *a.get("cascadeValues", {}), *(c for o in a.get("overlaps", []) for c in o.get("cells", []))]
    for c in cells:
        m = _CELL.fullmatch(c)
        if m:
            out.add((fname, m.group(1) or a["sheet"], int(m.group(2))))
    for x in [*a.get("crossFileAffected", []), *([a["crossFileLocation"]] if a.get("crossFileLocation") else [])]:
        if x.get("rowNo"):
            out.add((x["file"], x["sheet"], x["rowNo"]))
    return out


def _locations(a):
    """사고가 드러나는 (시트, 행). 행이 None이면 그 시트 어디든 (시트 단위 사고)"""
    if a.get("affected"):
        return [(x["sheet"], x["rowNo"]) for x in a["affected"]]
    if a.get("rowNo"):
        return [(a["sheet"], a["rowNo"])]
    rows = [r for r in a.get("rows", []) if isinstance(r, int)]
    if rows:
        return [(a["sheet"], r) for r in rows]
    return [(a["sheet"], None)]


def _read_check(up, meta, hk):
    """서비스가 읽은 행 수·헤더 vs 정답지 시트 구조"""
    expected_rows = None
    if meta.get("firstDataRow") and meta.get("lastDataRow"):
        expected_rows = (meta["lastDataRow"] - meta["firstDataRow"] + 1 + len(meta.get("rowsAfterTotal", []))
                         - len(meta.get("subtotalRows", [])))
    mapping = hk.get("expectedMapping", {}).get(meta.get("role"), {})
    want = {" ".join(h.split()) for h in mapping}
    got = {" ".join(str(h).split()) for h in up["headers"]}
    return {"sheetRead": up.get("sheetName"), "rows": up["rows"], "expectedRows": expected_rows,
            "headersMatched": len(want & got), "headersExpected": len(want), "headersSample": up["headers"][:4]}


def hr_section(results):
    lines = ["## 인사 엑셀", "", "### 읽기 (시트별)", "",
             "| 회사 | 파일 | 시트 | 읽은 시트 | 행 (읽음/정답) | 헤더 일치 | 파일 경고 |", "|---|---|---|---|---|---|---|"]
    by_type = defaultdict(lambda: {"expected": 0, "detected": 0, "codes": None})
    unexpected, needs = Counter(), Counter()
    legal = []
    for r in results:
        for f in r["hrFiles"]:
            for s in f["sheets"]:
                if "error" in s:
                    lines.append(f"| {r['company']} | {f['file']} | {s['sheet']} | 업로드 실패: {s['error']} | | | |")
                    continue
                rd = s["read"]
                rows = f"{rd['rows']}/{rd['expectedRows']}" + ("" if rd["rows"] == rd["expectedRows"] else " ✗")
                head = f"{rd['headersMatched']}/{rd['headersExpected']}" if rd["headersExpected"] else "-"
                lines.append(f"| {r['company']} | {f['file']} | {s['sheet']} | {rd['sheetRead'] or '(첫 시트)'} | {rows} | {head} "
                             f"| {', '.join(s['fileIssues']) or '-'} |")
            for a in f["accidents"]:
                t = by_type[a["type"]]
                t["expected"] += 1
                t["detected"] += a["detected"]
                t["codes"] = a["codes"]
            for d in f.get("legal", []):
                legal.append((r["company"], f["file"], d))
            unexpected.update(f["unexpected"])
            needs.update(f["needs"])
    total = sum(t["expected"] for t in by_type.values())
    hit = sum(t["detected"] for t in by_type.values())
    lines += ["", f"### 사고 검출: {hit}/{total}", "",
              "| 사고 유형 | 정답 | 검출 | 대응 이슈 코드 |", "|---|---|---|---|"]
    for k, t in sorted(by_type.items(), key=lambda x: (-x[1]["detected"], x[0])):
        lines.append(f"| {k} | {t['expected']} | {t['detected']} | {', '.join(t['codes']) if t['codes'] else '대응 기능 없음'} |")
    with_codes = [x for x in legal if x[2]["codes"]]
    lines += ["", f"### 법정 기준 편차 검출: {sum(x[2]['detected'] for x in with_codes)}/{len(with_codes)} (대응 기능이 있는 것) · 전체 {len(legal)}", "",
              "| 회사 | 파일 | 규칙 | 시트 | 검출 | 대응 이슈 코드 |", "|---|---|---|---|---|---|"]
    for company, fname, d in legal:
        mark = ("O" if d["detected"] else "X") if d["codes"] else "-"
        lines.append(f"| {company} | {fname} | {d['rule']} | {d['sheet'] or '-'} | {mark} | {', '.join(d['codes']) if d['codes'] else '대응 기능 없음'} |")
    lines += ["", f"- 정답지에 없는 경고 (대응표 코드 기준): {dict(unexpected) or '없음'}",
              "", "### 직원명부 대조 (파일 사이 참조)", "",
              "직원명부에 일부러 넣은 오류(사번 중복 → 한 사람 사번이 명부에서 사라짐, 같은 사람 두 번 → 이름 바뀜)가 다른 파일에서 드러난 것.", "",
              "| 회사 | 이슈 코드 | 건수 | 직원명부 오류로 설명됨 | 시트 수 |", "|---|---|---|---|---|"]
    for r in results:
        for code, c in r["reference"]["byCode"].items():
            lines.append(f"| {r['company']} | {code} | {c['issues']} | {c['explainedByRosterErrors']} | {c['sheets']} |")
    lines += ["",
              "", "### 사고를 잡는 데 필요한 기능 (정답지 needs 집계)", "", "| 기능 | 필요한 사고 수 |", "|---|---|"]
    lines += [f"| {k} | {v} |" for k, v in needs.most_common()]
    return lines + [""]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--base", default="http://localhost:8087")
    args = ap.parse_args()
    out = Path(args.out)
    results = [evaluate_company(args.base, d) for d in sorted(out.iterdir()) if (d / "answer_key.json").exists()]
    (out / "evaluation.json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")

    lines = ["# 이관 검증 평가 결과", ""]
    total_det = Counter()
    for r in results:
        lines.append(f"## {r['company']}")
        for entity, m in r["mapping"].items():
            lines.append(f"- 매핑 {entity}: {m['correct']}/{m['total']}" + (f" (틀림: {m['wrong']})" if m["wrong"] else ""))
        lines.append("")
        lines.append("| 오류 유형 | 정답 | 검출 | 검출률 | 비고 |")
        lines.append("|---|---|---|---|---|")
        for code, s in sorted(r["detection"].items()):
            rate = s["found"] / s["expected"] if s["expected"] else 0
            lines.append(f"| {code} | {s['expected']} | {s['found']} | {rate:.0%} | {'' if s['detectable'] else '1차에서 못 잡던 유형'} |")
            total_det["expected"] += s["expected"]
            total_det["found"] += s["found"]
        lines.append("")
        lines.append(f"- 오탐(정답지에 없는데 검출): {r['falsePositives'] or '없음'}")
        lines.append(f"- 자동 정리 제안(FIX, 오탐에서 뺌): {r['fixSuggestions'] or '없음'}")
        lines.append(f"- 파일 단위 오류: {r['fileLevel'] or '없음'}")
        lines.append(f"- 조직 파일 행 오류: {r['orgIssues']}")
        lines.append("")
    lines += hr_section(results)
    rate = total_det["found"] / total_det["expected"] if total_det["expected"] else 0
    lines.insert(2, "> 생성기의 오류 유형과 이관 서비스의 검증 규칙을 같은 쪽이 만들었다. 아래 검출률은 회귀 확인이지 정확도가 아니다.\n")
    lines.insert(2, f"**직원명부 검출률 (전체 유형): {total_det['found']}/{total_det['expected']} = {rate:.1%}**\n")
    (out / "evaluation.md").write_text("\n".join(lines), encoding="utf-8")


if __name__ == "__main__":
    main()
