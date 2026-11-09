"""
가상 회사 이관 데이터 생성기.

NVIDIA Nemotron-Personas-Korea (CC BY 4.0) 의 합성 인물을 직원으로 삼아,
회사마다 다른 양식·형식으로 '지저분한' 조직·직원 파일과 정답지(answer_key.json)를 만든다.

직원명부·조직에 더해 ERP 도입 전 회사가 쓰던 인사 엑셀(급여대장 등)도 만든다 (hrfiles/).

사용: python -I generate.py <personas.parquet> <출력 폴더> [--seed 42]
"""
import argparse
import copy
import csv
import datetime as dt
import json
import random
import re
import sys
from pathlib import Path

# python -I(격리 모드)는 스크립트 폴더를 import 경로에 넣지 않는다
sys.path.insert(0, str(Path(__file__).resolve().parent))

import pandas as pd
import pyarrow.parquet as pq
from openpyxl import Workbook

import newco
from hrfiles import appointments, attendance, insurance, leave, payroll, salary, severance
from hrfiles.facts import build_facts, month_range

CUTOVER = dt.date(2027, 1, 1)
NAME_RE = re.compile(r"^([가-힣]{2,4}) 씨")

# 직업 키워드 → (본부, 팀, 직군, 직종)
DEPT_RULES = [
    (("소프트웨어", "프로그래머", "개발자", "웹", "데이터", "시스템", "정보", "네트워크", "보안"), ("개발본부", "플랫폼개발팀", "개발", "SW")),
    (("디자이너", "디자인"), ("개발본부", "서비스기획팀", "개발", "기획·디자인")),
    (("영업", "판매", "상품"), ("영업본부", "영업1팀", "영업", "국내영업")),
    (("마케팅", "광고", "홍보"), ("영업본부", "마케팅팀", "영업", "마케팅")),
    (("상담", "고객"), ("영업본부", "고객지원팀", "영업", "고객지원")),
    (("회계", "경리", "재무", "세무", "출납"), ("경영지원본부", "재무팀", "경영지원", "재무회계")),
    (("인사", "노무", "교육"), ("경영지원본부", "인사팀", "경영지원", "인사")),
    (("생산", "조립", "기계", "설비", "제조", "가공", "용접", "조작원"), ("생산본부", "생산1팀", "생산", "제조")),
    (("품질", "검사"), ("생산본부", "품질관리팀", "생산", "품질")),
    (("물류", "운송", "하역", "배송", "창고"), ("생산본부", "물류팀", "생산", "물류")),
]
DEFAULT_DEPT = ("경영지원본부", "총무팀", "경영지원", "총무")

POSITIONS = [(29, "사원", "G1"), (34, "대리", "G2"), (40, "과장", "G3"), (46, "차장", "G4"), (99, "부장", "G5")]

# 회사별 양식: 컬럼명, 파일 형식, 날짜 표기
COMPANIES = [
    {
        "id": "ganatech", "name": "가나테크", "industry": "IT", "size": 120, "domain": "ganatech.co.kr",
        "format": "xlsx", "title_rows": ["2026년 임직원 명부", "작성: 인사팀 (2026-12-20 기준)"],
        "dept_code": lambda i: "D%03d" % (100 + i * 10),
        "date_style": "dot", "excel_dates": False,
        "columns": {"empNo": "사원번호", "name": "성 명", "email": "이메일", "dept": "소속", "position": "직위",
                    "grade": "직급", "duty": "직책", "jobFamily": "직군", "jobKind": "직종", "jobRole": "직무",
                    "hireDate": "입사년월일", "birthDate": "생년월일", "empType": "고용형태", "mobile": "휴대폰",
                    "status": "재직구분", "resignDate": "퇴사일", "_salary": "기본급", "_memo": "비고"},
        "org_columns": {"deptCode": "부서코드", "name": "부서명", "parentDeptCode": "상위부서"},
    },
    {
        "id": "daramulsan", "name": "다라물산", "industry": "제조", "size": 200, "domain": "daramulsan.com",
        "format": "csv_cp949", "title_rows": [],
        "dept_code": lambda i: "%04d" % (1000 + i),
        "date_style": "compact", "excel_dates": False,
        "columns": {"empNo": "사번", "name": "이름", "email": "E-mail", "dept": "부서명", "position": "직위",
                    "grade": "직급", "duty": "직책", "jobFamily": "직군", "jobKind": "직종", "jobRole": "직무",
                    "hireDate": "입사일자", "birthDate": "생일", "empType": "근로형태", "mobile": "연락처",
                    "status": "상태", "resignDate": "퇴직일"},
        "org_columns": {"deptCode": "조직코드", "name": "조직명", "parentDeptCode": "상위조직코드"},
    },
    {
        "id": "mabaservice", "name": "마바서비스", "industry": "서비스", "size": 80, "domain": "mabasvc.kr",
        "format": "xlsx", "title_rows": [],
        "dept_code": lambda i: "ORG-%02d" % (i + 1),
        "date_style": "iso", "excel_dates": True,
        "columns": {"empNo": "EMP_NO", "name": "NAME", "email": "EMAIL", "dept": "DEPT", "position": "POSITION",
                    "grade": "GRADE", "duty": "DUTY", "jobFamily": "JOB_FAMILY", "jobKind": "JOB_KIND",
                    "jobRole": "JOB_ROLE", "hireDate": "HIRE_DATE", "birthDate": "BIRTH_DATE",
                    "empType": "EMP_TYPE", "mobile": "MOBILE"},
        "org_columns": {"deptCode": "DEPT_CODE", "name": "DEPT_NAME", "parentDeptCode": "PARENT_CODE"},
    },
]

# 업종별로 더 많이 뽑을 부서
INDUSTRY_BIAS = {"IT": {"개발본부": 4.0, "영업본부": 1.5}, "제조": {"생산본부": 4.0}, "서비스": {"영업본부": 3.0}}


def load_personas(path, rng):
    cols = ["uuid", "persona", "sex", "age", "marital_status", "family_type", "education_level", "occupation", "district", "province"]
    df = pq.read_table(path, columns=cols).to_pandas()
    df = df[(df.age >= 24) & (df.age <= 59) & (df.occupation != "무직")].copy()
    df["name"] = df.persona.str.extract(NAME_RE)
    df = df.dropna(subset=["name"])
    df = df.sample(frac=1.0, random_state=rng.randint(0, 10_000)).reset_index(drop=True)
    return df


def dept_of(occupation):
    for keys, dept in DEPT_RULES:
        if any(k in occupation for k in keys):
            return dept
    return DEFAULT_DEPT


def pick_people(df, company, rng, used):
    bias = INDUSTRY_BIAS[company["industry"]]
    pool = df[~df.uuid.isin(used)]
    weights = pool.occupation.map(lambda o: bias.get(dept_of(o)[0], 1.0))
    picked = pool.sample(n=company["size"], weights=weights, random_state=rng.randint(0, 10_000))
    used.update(picked.uuid)
    return picked.to_dict("records")


def build_clean(company, people, rng):
    """정답 데이터: 조직 트리 + 직원"""
    teams = sorted({dept_of(p["occupation"])[:2] for p in people})
    hqs = sorted({hq for hq, _ in teams})
    org, code_of = [], {}
    for i, hq in enumerate(hqs):
        code_of[hq] = company["dept_code"](i)
        org.append({"deptCode": code_of[hq], "name": hq, "parentDeptCode": None})
    for j, (hq, team) in enumerate(teams):
        code_of[team] = company["dept_code"](len(hqs) + j)
        org.append({"deptCode": code_of[team], "name": team, "parentDeptCode": code_of[hq]})

    employees = []
    for n, p in enumerate(sorted(people, key=lambda x: -x["age"])):
        hq, team, family, kind = dept_of(p["occupation"])
        age = int(p["age"])
        birth = dt.date(CUTOVER.year - 1 - age, rng.randint(1, 12), rng.randint(1, 28))
        hire_age = rng.randint(23, min(age, 35))
        hire = dt.date(birth.year + hire_age, rng.randint(1, 12), rng.randint(1, 28))
        if hire >= CUTOVER:
            hire = CUTOVER - dt.timedelta(days=rng.randint(30, 400))
        position, grade = next((pos, g) for limit, pos, g in POSITIONS if age <= limit)
        emp_type = rng.choices(["정규직", "계약직", "파트타임"], [88, 9, 3])[0]
        resigned = rng.random() < 0.06
        resign = hire + dt.timedelta(days=rng.randint(200, max(201, (CUTOVER - hire).days - 1))) if resigned else None
        employees.append({
            "empNo": "%05d" % (100 + n),
            "name": p["name"],
            "email": "emp%05d@%s" % (100 + n, company["domain"]),
            "dept": team,
            "position": position, "grade": grade, "duty": "팀원",
            "jobFamily": family, "jobKind": kind, "jobRole": p["occupation"][:30],
            "hireDate": hire, "birthDate": birth, "empType": emp_type,
            "mobile": "010-%04d-%04d" % (rng.randint(1000, 9999), rng.randint(0, 9999)),
            "status": "퇴직" if resigned else "재직",
            "resignDate": resign,
            "_salary": 2_800_000 + 400_000 * POSITIONS.index(next(x for x in POSITIONS if x[1] == position)) + rng.randint(0, 30) * 10_000,
            "_memo": None,
            "_persona": p["uuid"],
            "_hq": hq, "_sex": p["sex"], "_familyType": p["family_type"],
        })
    # 팀마다 최연장자(재직)를 팀장으로
    seen = set()
    for e in employees:
        if e["dept"] not in seen and e["status"] == "재직":
            e["duty"] = "팀장"
            seen.add(e["dept"])
    return org, employees


def fmt_date(d, style, rng, messy_rate):
    """회사 기본 날짜 표기. messy_rate 확률로 다른 표기를 섞는다 (FIX 대상)"""
    if d is None:
        return None
    base = {"iso": d.isoformat(), "dot": "%d.%d.%d" % (d.year, d.month, d.day), "compact": d.strftime("%Y%m%d")}
    value = base[style]
    if rng.random() < messy_rate:
        alt = rng.choice([s for s in base if s != style])
        value = base[alt]
    return value


def inject_errors(company, employees, rng):
    """정답지에 남길 오류를 넣는다. kind는 이관 서비스 IssueCode 기준, detectable=False는 아직 못 잡는 유형"""
    errors = []
    idx = list(range(len(employees)))
    rng.shuffle(idx)
    take = lambda k: [idx.pop() for _ in range(k)]

    for i in take(2):  # 입사일 누락
        employees[i]["hireDate"] = None
        errors.append({"i": i, "field": "hireDate", "code": "REQUIRED"})
    for i in take(2):  # 이메일 오류
        employees[i]["email"] = employees[i]["email"].replace("@", "")
        errors.append({"i": i, "field": "email", "code": "INVALID_EMAIL"})
    i, j = take(2)  # 사번 중복
    employees[j]["empNo"] = employees[i]["empNo"]
    errors += [{"i": i, "field": "empNo", "code": "DUPLICATE_KEY"}, {"i": j, "field": "empNo", "code": "DUPLICATE_KEY"}]
    for i in take(1):  # 고용형태 오타
        employees[i]["empType"] = "정규"
        errors.append({"i": i, "field": "empType", "code": "INVALID_ENUM"})
    if "resignDate" in company["columns"]:
        for i in take(1):  # 퇴사일 < 입사일
            h = employees[i]["hireDate"]
            employees[i]["status"] = "퇴직"
            employees[i]["resignDate"] = h - dt.timedelta(days=rng.randint(10, 300))
            errors.append({"i": i, "field": "resignDate", "code": "DATE_ORDER"})
    i, j = take(2)  # 같은 사람이 두 번 (이름·생년월일 동일, 사번 다름)
    employees[j]["name"], employees[j]["birthDate"] = employees[i]["name"], employees[i]["birthDate"]
    errors += [{"i": i, "field": "name", "code": "DUPLICATE_SUSPECTED"}, {"i": j, "field": "name", "code": "DUPLICATE_SUSPECTED"}]
    for i in take(2):  # 직위 오타 — 코드 매핑이 생기기 전까지는 못 잡음
        employees[i]["position"] = {"과장": "과정", "대리": "데리", "사원": "샤원", "차장": "차쟝", "부장": "부쟝"}[employees[i]["position"]]
        errors.append({"i": i, "field": "position", "code": "POSITION_TYPO", "detectable": False})
    return errors


def write_company(company, org, employees, errors, out_dir, rng):
    cdir = out_dir / company["id"]
    cdir.mkdir(parents=True, exist_ok=True)
    cols = company["columns"]
    style = company["date_style"]

    rows = []
    for e in employees:
        row = {}
        for field, header in cols.items():
            v = e.get(field)
            if field in ("hireDate", "birthDate", "resignDate"):
                v = v if company["excel_dates"] and isinstance(v, dt.date) and rng.random() > 0.15 else fmt_date(v, style, rng, 0.08)
            row[header] = v
        rows.append(row)

    # 날짜 표기 차이(FIX)는 생성 결과를 보고 정답지에 기록
    iso = re.compile(r"^\d{4}-\d{2}-\d{2}$")
    for k, row in enumerate(rows):
        for field in ("hireDate", "birthDate", "resignDate"):
            v = row.get(cols.get(field))
            if isinstance(v, str) and not iso.match(v):
                errors.append({"i": k, "field": field, "code": "DATE_FORMAT"})

    headers = list(cols.values())
    first_data_row = len(company["title_rows"]) + (1 if company["title_rows"] else 0) + 2  # 제목 + 빈 행 + 헤더
    if company["format"] == "xlsx":
        wb = Workbook()
        ws = wb.active
        for t in company["title_rows"]:
            ws.append([t])
        if company["title_rows"]:
            ws.append([])
        ws.append(headers)
        for row in rows:
            ws.append([row[h] for h in headers])
            for c, h in enumerate(headers, start=1):
                if isinstance(row[h], dt.date):
                    ws.cell(row=ws.max_row, column=c).number_format = "yyyy-mm-dd"
        wb.save(cdir / "직원명부.xlsx")
        emp_file = "직원명부.xlsx"
    else:
        with open(cdir / "직원명부.csv", "w", encoding="cp949", newline="") as f:
            w = csv.writer(f)
            w.writerow(headers)
            for row in rows:
                w.writerow(["" if row[h] is None else row[h] for h in headers])
        emp_file = "직원명부.csv"
        first_data_row = 2

    oc = company["org_columns"]
    with open(cdir / "조직.csv", "w", encoding="cp949" if company["format"] == "csv_cp949" else "utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(oc.values())
        for o in org:
            w.writerow(["" if o[k] is None else o[k] for k in oc])

    expected_mapping = {h: (None if f.startswith("_") else f) for f, h in cols.items()}
    key = {
        "company": company["name"], "industry": company["industry"], "cutoverDate": CUTOVER.isoformat(),
        "files": {"HR_EMPLOYEE": emp_file, "HR_ORG": "조직.csv"},
        "expectedMapping": {"HR_EMPLOYEE": expected_mapping,
                            "HR_ORG": {h: f for f, h in oc.items()}},
        "errors": sorted(
            [{"rowNo": first_data_row + e["i"], "field": e["field"], "code": e["code"],
              "detectable": e.get("detectable", True)} for e in errors],
            key=lambda x: (x["rowNo"], x["field"])),
        "counts": {"employees": len(employees), "orgs": len(org)},
        "source": "NVIDIA Nemotron-Personas-Korea (CC BY 4.0) 의 합성 인물을 사용",
    }
    return key


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("personas")
    ap.add_argument("out")
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()
    rng = random.Random(args.seed)
    out_dir = Path(args.out)

    df = load_personas(args.personas, rng)
    used = set()
    summary = []
    for company in COMPANIES:
        people = pick_people(df, company, rng, used)
        org, employees = build_clean(company, people, rng)
        facts = build_facts(company, employees, month_range("2026-01", "2026-12"), args.seed)
        for e in employees:  # 직원명부의 기본급 = 연봉 / 12 (회사별 끝수 처리)
            e["_salary"] = facts[e["empNo"]]["basePay"]
        clean = copy.deepcopy(employees)  # 오류를 넣기 전 명부: 인사 파일들은 이 명부로 만든다
        errors = inject_errors(company, employees, rng)
        key = write_company(company, org, employees, errors, out_dir, rng)
        cdir = out_dir / company["id"]
        hr_rng = random.Random("%s:hr:%d" % (company["id"], args.seed))
        file_rng = lambda name: random.Random("%s:%s:%d" % (company["id"], name, args.seed))  # noqa: E731 - 파일마다 따로
        key["hrFiles"] = {
            "급여대장.xlsx": payroll.build(company, clean, facts, cdir, hr_rng),
            "연차관리대장.xlsx": leave.build(company, clean, facts, cdir, file_rng("leave")),
        }
        att, reg = attendance.build(company, clean, facts, cdir, file_rng("attendance"), key["hrFiles"]["급여대장.xlsx"])
        key["hrFiles"].update({"근태기록.xlsx": att, "연장근무신청대장.xlsx": reg})
        key["hrFiles"]["인사발령대장.xlsx"] = appointments.build(company, clean, cdir, file_rng("appointments"))
        key["hrFiles"]["연봉이력.xlsx"] = salary.build(company, clean, facts, cdir, file_rng("salary"))
        key["hrFiles"]["퇴직금추계표.xlsx"] = severance.build(company, clean, facts, cdir, file_rng("severance"))
        ins, dep = insurance.build(company, clean, facts, cdir, file_rng("insurance"))
        key["hrFiles"].update({"4대보험취득상실.xlsx": ins, "부양가족.xlsx": dep})
        (cdir / "answer_key.json").write_text(json.dumps(key, ensure_ascii=False, indent=2), encoding="utf-8")
        by_code = {}
        for e in key["errors"]:
            by_code[e["code"]] = by_code.get(e["code"], 0) + 1
        summary.append({"company": company["name"], **key["counts"], "errors": by_code})
    # 추가 회사(newco/): 기존 3개 회사를 다 만든 뒤, 회사별 난수로 만든다 (위 난수열·출력에 영향 없음)
    summary += newco.build_all(df, used, out_dir, args.seed)
    (out_dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
