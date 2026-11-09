"""
시연용 이관 파일: 조직.xlsx(HR_ORG 32행)와 사원.xlsx(HR_EMPLOYEE 1,252행), 급여대장_2026-11.xlsx(검증용).

프론트 시연 대본(DOCS/frontend-design.md 6.7·8장, frontend-requirements.md 8장 #11)에 맞춘다.
- 조직: 회사 1 + 본부 5 + 팀 26. 부서유형·부서장 사번이 모두 맞게 채워져 있어 이슈 없이 바로 적재된다
- 사원: 날짜 표기 섞임(자동 정리), 고용형태 줄임말(AI 값 추천), 휴대폰 앞자리 0 빠짐(자동 정리), 입사일 빈 칸 1건(적재 불가)
- 사원_적재불가0.xlsx: 입사일 빈 칸만 채운 판. 행 수정(P1)을 빼면 이 파일로 시연한다
직위·직급·직책·직군·직종·직무는 user-service 개발 시드(R__dev_seed.sql)에 있는 값만 쓴다 (없는 값은 적재 때 행 오류).
직급은 시드가 G1~G6 × 1~5호봉이라 'G3 2호봉'처럼 호봉까지 적는다. 시드에 생산·연구 직군이 없어 IT 회사로 짠다.
- 급여대장_2026-11.xlsx: 2026년 11월 재직자 급여. 기본급은 NEXA 방식(직급·호봉별 기본급 표, payroll 개발 시드 pay_grade와 같은 금액)이고
  계산 칸은 진짜 엑셀 수식이다. 급여 쪽 사고 6종을 몇 건씩 넣는다 (PAYROLL_ACCIDENTS)
가상 회사 데이터와 같은 합성 인물(Nemotron-Personas-Korea)을 쓴다. 이름 말고는 인물 정보를 쓰지 않는다.

사용: python -I demo.py <personas.parquet> <출력 폴더> [--seed 7]
"""
import argparse
import datetime as dt
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from openpyxl import Workbook

from generate import POSITIONS, dept_of, load_personas
from hrfiles.payroll import RATES, simple_tax
from hrfiles.xl import ROUNDDOWN, SUM, Book

COMPANY = "넥사데모(주)"
DOMAIN = "nexa-demo.example"
CUTOVER = dt.date(2026, 12, 1)
SIZE = 1252

# 본부 → (팀, 직군, 직종, 직무들, 인원). 인원 합 + 대표 1 + 본부장 5 = SIZE. 직무가 여럿이면 번갈아 준다
DEV, FE, OPS, QA = ("IT", "개발", ("백엔드",)), ("IT", "개발", ("프론트엔드",)), ("IT", "인프라", ("클라우드 운영",)), ("IT", "QA", ("테스트",))
SALES = ("영업", "국내영업", ("법인영업",))
HQS = {
    "경영지원본부": [("인사팀", "경영지원", "인사", ("HR 운영", "채용"), 28), ("총무팀", "경영지원", "총무", ("총무",), 34),
                ("재무팀", "경영지원", "재무", ("회계",), 31), ("교육팀", "경영지원", "인사", ("교육",), 12),
                ("자금팀", "경영지원", "재무", ("자금",), 25)],
    "영업본부": [("영업1팀", *SALES, 62), ("영업2팀", *SALES, 58), ("영업3팀", *SALES, 55),
             ("해외영업팀", "영업", "해외영업", ("해외영업",), 40), ("마케팅팀", "영업", "마케팅", ("디지털마케팅",), 38),
             ("영업지원팀", *SALES, 91)],
    "개발본부": [("플랫폼개발팀", *DEV, 54), ("서비스개발팀", "IT", "개발", ("백엔드", "프론트엔드"), 80), ("데이터팀", *DEV, 36),
             ("인프라팀", *OPS, 30), ("보안팀", *OPS, 18), ("프론트엔드팀", *FE, 32)],
    "서비스운영본부": [("운영1팀", *OPS, 120), ("운영2팀", *OPS, 110), ("운영3팀", *OPS, 80),
                ("품질관리팀", *QA, 46), ("장애대응팀", *OPS, 34), ("테스트자동화팀", *QA, 52)],
    "연구소": [("연구1팀", *DEV, 30), ("연구2팀", *DEV, 25), ("연구3팀", *FE, 25)],
}
# 이름만 쓰는 합성 인물을 어느 직업군에서 뽑을지 (generate.py의 본부 이름)
POOL_OF = {"서비스운영본부": "생산본부", "연구소": "개발본부"}

EMP_HEADERS = ["사번", "성명", "이메일", "부서", "직위", "직급", "직책", "직군", "직종", "직무", "고용형태",
               "입사일", "생년월일", "성별", "휴대폰", "재직상태", "퇴사일"]
ORG_HEADERS = ["부서코드", "부서명", "상위부서", "부서유형", "부서장 사번", "정렬순서"]


def build(personas, rng):
    """정상 데이터: 조직 32행, 사원 SIZE행 (나이가 많은 순으로 대표·본부장·팀장)"""
    org = [{"code": "D000", "name": COMPANY, "parent": None, "type": "회사"}]
    seats = []  # (본부, 팀, 직군, 직종, 직무들, 인원)
    for h, (hq, teams) in enumerate(HQS.items()):
        hq_code = "D%d00" % (h + 1)
        org.append({"code": hq_code, "name": hq, "parent": "D000", "type": "본부"})
        for t, (team, family, kind, roles, n) in enumerate(teams):
            org.append({"code": "D%d%02d" % (h + 1, (t + 1) * 10), "name": team, "parent": hq_code, "type": "팀"})
            seats.append((hq, team, family, kind, roles, n))
    assert len(org) == 32 and sum(s[5] for s in seats) + 1 + len(HQS) == SIZE

    pools = {hq: [] for hq in HQS}
    for p in personas:
        hq = dept_of(p["occupation"])[0]
        for target in HQS:
            if POOL_OF.get(target, target) == hq:
                pools[target].append(p)
    rest = iter(p for p in personas)
    used = set()

    def take(hq, oldest=False):
        if oldest:  # 대표·본부장은 그 직업군에서 나이가 가장 많은 사람
            pools[hq].sort(key=lambda p: (p["uuid"] not in used, p["age"]))
        while pools[hq]:
            p = pools[hq].pop()
            if p["uuid"] not in used:
                used.add(p["uuid"])
                return p
        for p in rest:
            if p["uuid"] not in used:
                used.add(p["uuid"])
                return p
        raise RuntimeError("인물이 모자랍니다")

    people = [("D000", COMPANY, "경영지원", "총무", "총무", take("경영지원본부", oldest=True))]
    for hq in HQS:  # 본부장은 첫 팀의 직무
        people.append((next(o["code"] for o in org if o["name"] == hq), hq, *HQS[hq][0][1:3], HQS[hq][0][3][0], take(hq, oldest=True)))
    for hq in HQS:  # 나머지는 섞어서
        rng.shuffle(pools[hq])
    for hq, team, family, kind, roles, n in seats:
        code = next(o["code"] for o in org if o["name"] == team)
        people += [(code, team, family, kind, roles[i % len(roles)], take(hq)) for i in range(n)]

    employees, head_of = [], {}
    for n, (code, dept, family, kind, role, p) in enumerate(people):
        age = int(p["age"])
        birth = dt.date(CUTOVER.year - 1 - age, rng.randint(1, 12), rng.randint(1, 28))
        hire = dt.date(birth.year + rng.randint(23, min(age, 35)), rng.randint(1, 12), rng.randint(1, 28))
        if hire >= CUTOVER:
            hire = CUTOVER - dt.timedelta(days=rng.randint(30, 400))
        position, grade = next((pos, g) for limit, pos, g in POSITIONS if age <= limit)
        step = 1 + (CUTOVER.year - hire.year) % 5  # 호봉 1~5 (시드 직급이 호봉 단위)
        employees.append({
            "empNo": "N%05d" % (n + 1), "name": p["name"], "email": "n%05d@%s" % (n + 1, DOMAIN),
            "dept": dept, "code": code, "position": position, "grade": "%s %d호봉" % (grade, step), "duty": "팀원",
            "jobFamily": family, "jobKind": kind, "jobRole": role,
            "empType": rng.choices(["정규직", "계약직", "파트타임"], [90, 8, 2])[0],
            "hireDate": hire, "birthDate": birth, "gender": "남" if p["sex"] == "남자" else "여",
            "mobile": "010-%04d-%04d" % (rng.randint(1000, 9999), rng.randint(0, 9999)),
            "status": "재직", "resignDate": None,
        })
    # 부서마다 가장 나이 많은 사람을 부서장으로 (대표이사·본부장·팀장)
    duty_of = {"회사": "대표이사", "본부": "본부장", "팀": "팀장"}
    type_of = {o["code"]: o["type"] for o in org}
    for e in sorted(employees, key=lambda e: e["birthDate"]):
        if e["code"] not in head_of:
            head_of[e["code"]] = e["empNo"]
            e["duty"] = duty_of[type_of[e["code"]]]
            e["empType"] = "정규직"
    for o in org:
        o["head"] = head_of[o["code"]]
    # 퇴직자 약 4% (부서장 제외)
    for e in employees:
        if e["duty"] == "팀원" and rng.random() < 0.04:
            e["status"] = "퇴직"
            e["resignDate"] = min(CUTOVER - dt.timedelta(days=1), e["hireDate"] + dt.timedelta(days=rng.randint(200, 3000)))
    return org, employees


def dirty(employees, rng):
    """시연용 문제를 넣는다. 값 오류는 재직 중인 팀원에게만 넣는다. 넣은 건수를 돌려준다"""
    members = [e for e in employees if e["duty"] == "팀원" and e["status"] == "재직"]
    rng.shuffle(members)
    counts = {"DATE_FORMAT": 0}
    for e in employees:  # 날짜 표기 섞임 → 자동 정리
        for field in ("hireDate", "birthDate"):
            if rng.random() < 0.08:
                d = e[field]
                e[field] = rng.choice(["%d.%d.%d" % (d.year, d.month, d.day), d.strftime("%Y%m%d"), d.strftime("%Y/%m/%d")])
                counts["DATE_FORMAT"] += 1
    for e, v in zip(members[:8], ["정규"] * 5 + ["계약"] * 3):  # 줄임말 → AI 값 추천
        e["empType"] = v
    for e in members[8:20]:  # 숫자 칸으로 저장돼 앞자리 0이 빠진 휴대폰 → 자동 정리
        e["mobile"] = e["mobile"].replace("-", "")[1:]
    members[20]["hireDate"] = None  # 입사일 빈 칸 → 적재 불가
    counts["DATE_FORMAT"] = sum(isinstance(e[f], str) for e in employees for f in ("hireDate", "birthDate"))
    counts.update(ENUM_VALUE_SUGGESTED=8, PHONE_ZERO_DROPPED=12, REQUIRED=1)
    return counts, members[20]


# payroll-service 개발 시드 pay_grade: 직급별 1호봉 금액에서 호봉마다 10만원씩
GRADE_BASE = {"G1": 3_500_000, "G2": 4_000_000, "G3": 4_600_000, "G4": 5_300_000, "G5": 6_100_000, "G6": 7_500_000}
DUTY_ALLOWANCE = {"대표이사": 1_000_000, "본부장": 500_000, "팀장": 300_000}
MEAL = 200_000
PAY_MONTH = "2026-11"
PAY_HEADERS = ["사번", "성명", "부서", "직급", "기본급", "직책수당", "식대", "연장근로시간", "통상시급", "연장근로수당", "지급계",
               "과세대상", "국민연금", "건강보험", "장기요양", "고용보험", "소득세", "지방소득세", "공제계", "실지급액"]
# 넣는 사고와 이관이 내야 할 이슈 코드
PAYROLL_ACCIDENTS = {
    "INSURANCE_RATE_OUTDATED": "설정 시트 국민연금 요율을 작년(4.5%) 그대로 둠",
    "BELOW_MINIMUM_WAGE": "기본급 0 하나 빠짐 (1행)",
    "FORMULA_INCONSISTENT": "연장근로수당 수식 배율 1.5 → 1.0 (2행), 같은 행이 PREMIUM_BELOW_STATUTORY",
    "HARDCODED_VALUE": "실지급액을 손으로 덮어씀 (1행)",
    "PAID_OUTSIDE_EMPLOYMENT": "11월 전에 퇴사한 사람에게 11월 급여 (1행)",
    "EMP_NOT_IN_ROSTER": "사번 오타 (1행)",
}


def pay_base(grade):
    """'G5 2호봉' → 기본급"""
    name, step = grade.split()
    return GRADE_BASE[name] + (int(step.rstrip("호봉")) - 1) * 100_000


def write_payroll(path, employees, rng):
    """2026-11 급여대장. 계산 칸은 수식 + 파이썬 계산값 (이관은 저장된 값을 읽는다)"""
    ym = dt.date(2026, 11, 1)
    paid = [e for e in employees if e["status"] == "재직"]
    # 11월 전에 퇴사한 사람 중 가장 최근 (퇴사 처리 누락으로 급여가 한 번 더 나간 경우)
    resigned = sorted((e for e in employees if e["status"] == "퇴직" and e["resignDate"] < ym), key=lambda e: e["resignDate"])[-1:]
    members = [i for i, e in enumerate(paid) if e["duty"] == "팀원"]
    rng.shuffle(members)
    low_base, x10, x10b, typed, typo = members[:5]
    rows = paid[:]
    rows.insert(rng.randrange(len(rows)), resigned[0])  # 퇴사자 급여

    wrong_rate = RATES[2025]["np"]
    book = Book(path)
    st = book.sheet("설정")
    st.put("A", 1, "항목", "head")
    st.put("B", 1, "값", "head")
    for r, (label, v) in enumerate([("국민연금", wrong_rate), ("건강보험", RATES[2026]["hi"]),
                                    ("장기요양(건강보험료 대비)", RATES[2026]["ltc"]), ("고용보험", RATES[2026]["ei"])], start=2):
        st.put("A", r, label)
        st.put("B", r, v, "pct")
    rate = {"np": wrong_rate, "hi": RATES[2026]["hi"], "ltc": RATES[2026]["ltc"], "ei": RATES[2026]["ei"]}

    sh = book.sheet(PAY_MONTH)
    sh.put("A", 1, "%s 2026년 11월 급여대장" % COMPANY, "title")
    for c, h in enumerate(PAY_HEADERS):
        sh.put(chr(65 + c), 2, h, "head")
    for i, e in enumerate(rows):
        r = i + 3
        idx = paid.index(e) if e in paid else -1
        base = pay_base(e["grade"])
        if idx == low_base:
            base //= 10
        duty = DUTY_ALLOWANCE.get(e["duty"], 0)
        ot = rng.choice([0, 0, 0, 4, 8, 12, 16, 20])
        emp_no = e["empNo"]
        if idx == typo:
            emp_no = emp_no.replace("N0", "NO", 1)  # 숫자 0 대신 영문 O
        mult = 1.0 if idx in (x10, x10b) else 1.5
        hourly = ROUNDDOWN((base + duty) / 209, 0)
        ot_pay = ROUNDDOWN(hourly * ot * mult, 0)
        gross = SUM(base, duty, MEAL, ot_pay)
        taxable = gross - MEAL
        np_ = ROUNDDOWN(taxable * rate["np"], -1)
        hi = ROUNDDOWN(taxable * rate["hi"], -1)
        ltc = ROUNDDOWN(hi * rate["ltc"], -1)
        ei = ROUNDDOWN(taxable * rate["ei"], -1)
        tax = simple_tax(taxable, 1)
        local = ROUNDDOWN(tax * 0.1, -1)
        ded = SUM(np_, hi, ltc, ei, tax, local)
        net = gross - ded
        for col, v in zip("ABCD", [emp_no, e["name"], e["dept"], e["grade"]]):
            sh.put(col, r, v)
        sh.put("E", r, base, "won")
        sh.put("F", r, duty, "won")
        sh.put("G", r, MEAL, "won")
        sh.put("H", r, ot, "num")
        sh.formula("I", r, "=ROUNDDOWN((E%d+F%d)/209,0)" % (r, r), hourly, "won")
        sh.formula("J", r, "=ROUNDDOWN(I%d*H%d*%s,0)" % (r, r, "1" if mult == 1.0 else "1.5"), ot_pay, "won")
        sh.formula("K", r, "=SUM(E%d:G%d)+J%d" % (r, r, r), gross, "won")
        sh.formula("L", r, "=K%d-G%d" % (r, r), taxable, "won")
        sh.formula("M", r, "=ROUNDDOWN(L%d*설정!$B$2,-1)" % r, np_, "won")
        sh.formula("N", r, "=ROUNDDOWN(L%d*설정!$B$3,-1)" % r, hi, "won")
        sh.formula("O", r, "=ROUNDDOWN(N%d*설정!$B$4,-1)" % r, ltc, "won")
        sh.formula("P", r, "=ROUNDDOWN(L%d*설정!$B$5,-1)" % r, ei, "won")
        sh.put("Q", r, tax, "won")
        sh.formula("R", r, "=ROUNDDOWN(Q%d*0.1,-1)" % r, local, "won")
        sh.formula("S", r, "=SUM(M%d:R%d)" % (r, r), ded, "won")
        if idx == typed:
            sh.put("T", r, net // 1000 * 1000 + 1000, "won")  # 실지급액을 손으로 맞춰 적음
        else:
            sh.formula("T", r, "=K%d-S%d" % (r, r), net, "won")
    sh.widths({"A": 9, "B": 8, "C": 12, "D": 10})
    book.close()
    return len(rows), resigned[0]["empNo"]


def text(v):
    return v.isoformat() if isinstance(v, dt.date) else v


def write(path, headers, rows):
    wb = Workbook()
    ws = wb.active
    ws.append(headers)
    for r in rows:
        ws.append([text(v) for v in r])
    wb.save(path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("personas")
    ap.add_argument("out")
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    rng = random.Random(args.seed)
    personas = load_personas(args.personas, rng).head(SIZE * 4).to_dict("records")
    org, employees = build(personas, rng)
    counts, blocked = dirty(employees, rng)

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    name_of = {o["code"]: o["name"] for o in org}
    write(out / "조직.xlsx", ORG_HEADERS,
          [[o["code"], o["name"], o["parent"], o["type"], o["head"], i + 1] for i, o in enumerate(org)])
    keys = ["empNo", "name", "email", "dept", "position", "grade", "duty", "jobFamily", "jobKind", "jobRole", "empType",
            "hireDate", "birthDate", "gender", "mobile", "status", "resignDate"]
    write(out / "사원.xlsx", EMP_HEADERS, [[e[k] for k in keys] for e in employees])
    blocked["hireDate"] = "2019-03-04"
    write(out / "사원_적재불가0.xlsx", EMP_HEADERS, [[e[k] for k in keys] for e in employees])
    assert all(o["parent"] is None or o["parent"] in name_of for o in org)
    print("조직 %d행, 사원 %d행 → %s" % (len(org), len(employees), out))
    print("넣은 문제:", counts, "(적재 불가 행 사번 %s)" % blocked["empNo"])
    # 급여대장은 사원 파일과 따로 섞어 사원 파일 결과가 바뀌지 않게 한다
    n, resigned = write_payroll(out / ("급여대장_%s.xlsx" % PAY_MONTH), employees, random.Random(args.seed + 1))
    print("급여대장 %d행 (퇴사자 %s 포함), 넣은 사고: %s" % (n, resigned, ", ".join(PAYROLL_ACCIDENTS)))


if __name__ == "__main__":
    main()
