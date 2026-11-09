"""
퍼허그룹 — 계열사 3개 법인(퍼허홀딩스 25·퍼허푸드 85·퍼허테크 50)의 인사를 노무법인이 대행. 세 법인이 한 파일에 들어 있다.

파일: 직원명부.xlsx(통합명부 한 시트, 법인코드 열, 사번은 법인마다 0001부터라 법인끼리 겹침(법인코드+사번이 키), 그룹입사일·입사일 두 날짜),
     조직.csv(UTF-8 BOM, 그룹 → 법인 → 부서 3단계),
     급여대장_2026-12.xlsx(노무법인 표준양식 한 시트에 법인별 블록: '■ 법인' 머리 행 → 직원 → 소계, 맨 아래 총계, 법인별집계 시트가 소계 셀을 참조),
     연차대장_2026.xlsx(법인마다 시트, 발생 규칙이 법인마다 다름: 홀딩스 회계연도, 푸드·테크 입사일).
급여 규칙(법인 공통 양식): 통상시급 = ROUNDDOWN((기본급+직책수당)/209), 연장 1.5, 식대·자가운전 각 20만 비과세, 4대보험·지방소득세 10원 미만 버림.
법인별 차이(오류 아님): 식대 홀딩스·테크 20만 / 푸드 15만, 자가운전보조금은 테크 영업·개발 일부만, 연차 기준(회계연도/입사일).
그룹 규정: 계열사 전적 시 근속 승계 (가정) → 연차는 그룹입사일 기준.
"""
import datetime as dt

from xlsxwriter.utility import xl_col_to_name

from hrfiles.common import Accidents
from hrfiles.xl import ROUND, ROUNDDOWN, Book, datedif_m, datedif_y

from . import base as B

CID, NAME = "peoheogroup", "퍼허그룹"
PAY_FILE, LEAVE_FILE = "급여대장_2026-12.xlsx", "연차대장_2026.xlsx"
CORPS = [("PH", "퍼허홀딩스", ["경영기획팀", "재무팀", "인사총무팀"], 25),
         ("PF", "퍼허푸드", ["생산1팀", "생산2팀", "품질관리팀", "물류팀", "영업팀", "관리팀"], 85),
         ("PT", "퍼허테크", ["개발1팀", "개발2팀", "인프라팀", "영업팀", "경영지원팀"], 50)]
CORP_NAME = {c: n for c, n, _d, _s in CORPS}
MEAL = {"PH": 200_000, "PF": 150_000, "PT": 200_000}
BASE = dt.date(2026, 12, 31)


def people(df, used, seed):
    rng = B.rng_for(CID, "people", seed)
    total = sum(s for *_x, s in CORPS)
    picked = B.pick(df, used, total, rng, CID)
    picked.sort(key=lambda p: p["uuid"])
    rng.shuffle(picked)
    emps, k = [], 0
    for corp, cname, depts, size in CORPS:
        group = sorted(picked[k:k + size], key=lambda p: (-p["age"], p["uuid"]))
        k += size
        for j, p in enumerate(group):
            age = int(p["age"])
            birth = B.birth_of(age, rng)
            hire = min(dt.date(max(1999, birth.year + rng.randint(24, 32)), rng.choice([1, 3, 7, 9]), rng.choice([1, 2])), dt.date(2026, 7, 1))
            dept = depts[0] if j == 0 and corp == "PH" else rng.choice(depts)
            prod = dept.startswith("생산") or dept == "물류팀"
            pos = "대표이사" if j == 0 else "부장" if age >= 50 else "차장" if age >= 45 else "과장" if age >= 39 else "대리" if age >= 33 else "사원"
            if prod and pos not in ("대표이사", "부장"):
                pos = "반장" if age >= 45 else "사원"
            base = {"대표이사": 9_000_000, "부장": 6_000_000, "차장": 5_200_000, "과장": 4_500_000, "대리": 3_800_000, "사원": 3_100_000,
                    "반장": 3_300_000}[pos]
            if corp == "PF":
                base = int(base * 0.82) // 10_000 * 10_000
            if prod and pos == "사원":
                base = 2_400_000 + rng.randint(0, 20) * 10_000
            emps.append({"corp": corp, "name": p["name"], "birthDate": birth, "sex": p["sex"], "age": age, "dept": dept, "position": pos,
                         "duty": None, "hireDate": hire, "groupHire": hire, "resignDate": None, "status": "재직", "prod": prod,
                         "empType": "정규직" if rng.random() < 0.9 else "계약직", "mobile": B.phone(rng), "basePay": base,
                         "family": rng.choice([1, 1, 2, 3, 4]), "car": 200_000 if corp == "PT" and dept in ("영업팀", "개발1팀") and rng.random() < 0.5 else 0,
                         "otH": rng.choice([0, 4, 8, 12, 16, 20]) if prod else rng.choice([0, 0, 2, 4, 6])})
    for corp, _n, depts, _s in CORPS:
        for d in depts:
            m = [e for e in emps if e["corp"] == corp and e["dept"] == d and e["position"] != "대표이사"]
            if m:
                lead = max(m, key=lambda e: (e["age"], e["name"]))
                lead["duty"] = "팀장"
    for e in emps:
        e["dutyAlw"] = 500_000 if e["position"] == "대표이사" else 200_000 if e["duty"] == "팀장" else 0
    # 계열사 전적: 2024-07-01 푸드 → 테크 1명(근속 승계), 2026-12-01 푸드 → 테크 1명(12월 사고), 2025-03-01 테크 → 홀딩스 1명(명부 정상 이력)
    pf = sorted([e for e in emps if e["corp"] == "PF" and not e["prod"] and e["position"] in ("대리", "과장") and e["hireDate"] < dt.date(2020, 1, 1)],
                key=lambda e: e["name"])
    rng.shuffle(pf)
    for e, when in ((pf[0], dt.date(2024, 7, 1)), (pf[1], dt.date(2026, 12, 1))):
        e["transfer"] = when
    pt = sorted([e for e in emps if e["corp"] == "PT" and e["hireDate"] < dt.date(2022, 1, 1) and e["position"] in ("대리", "과장")],
                key=lambda e: e["name"])
    pt[0]["transfer"] = dt.date(2025, 3, 1)
    for e in list(emps):
        if "transfer" not in e:
            continue
        to = "PT" if e["corp"] == "PF" else "PH"
        old = dict(e)
        old.update(resignDate=e["transfer"] - dt.timedelta(days=1), status="퇴사(전적)", transferTo=to)
        old.pop("transfer")
        e.update(corp=to, hireDate=e["transfer"], dept=CORPS[[c for c, *_x in CORPS].index(to)][2][0], prevCorp=old)
        emps.append(old)
    emps.sort(key=lambda e: (e["corp"], e["hireDate"], e["name"]))
    seq = {}
    for e in emps:
        seq[e["corp"]] = seq.get(e["corp"], 0) + 1
        e["empNo"] = "%04d" % seq[e["corp"]]
        e["email"] = "%s.%s@peoheo.co.kr" % (e["corp"].lower(), e["empNo"])
    # 2025-12-31 퇴사자 1명: 명부에만 남은 퇴사 이력 (2026 급여·연차에는 없음). 명부 DATE_ORDER 오류는 이 사람 퇴사일에 넣는다
    cand = sorted([e for e in emps if e["corp"] == "PF" and e["status"] == "재직" and "transfer" not in e and not e.get("transferTo")
                   and e["position"] != "대표이사" and e["duty"] is None and e["hireDate"] < dt.date(2024, 1, 1)], key=lambda e: e["empNo"])
    rng.choice(cand).update(status="퇴사", resignDate=dt.date(2025, 12, 31))
    return emps


# ================================================================ 급여대장 (법인별 블록 한 시트)

COLS = [("corp", "법인코드"), ("empNo", "사번"), ("name", "성명"), ("dept", "부서"), ("position", "직위"), ("family", "공제가족"),
        ("basePay", "기본급"), ("dutyAlw", "직책수당"), ("otH", "연장시간"), ("hourly", "통상시급"), ("otPay", "연장수당"), ("meal", "식대"),
        ("car", "자가운전"), ("gross", "지급계"), ("np", "국민연금"), ("hi", "건강보험"), ("ltc", "장기요양"), ("ei", "고용보험"),
        ("tax", "소득세"), ("ltax", "지방소득세"), ("ded", "공제계"), ("net", "실지급액"), ("memo", "비고")]
L = {k: xl_col_to_name(i) for i, (k, _) in enumerate(COLS)}
SUMMED = [k for k, _ in COLS[COLS.index(("basePay", "기본급")):-1] if k not in ("hourly",)]
SETS = [("np", "국민연금", B.RATES_2026["np"], "pct"), ("hi", "건강보험", B.RATES_2026["hi"], "pct"), ("ltc", "장기요양", B.RATES_2026["ltc"], "pct"),
        ("ei", "고용보험", B.RATES_2026["ei"], "pct"), ("limit", "식대·자가운전 비과세 한도(각)", B.NONTAX_LIMIT, "won"),
        ("npFloor", "국민연금 하한", B.NP_FLOOR, "won"), ("npCap", "국민연금 상한", B.NP_CAP, "won")]


def calc(v, S, full_nontax=False, stale_gross=None):
    v = dict(v)
    v["hourly"] = ROUNDDOWN((v["basePay"] + v["dutyAlw"]) / 209, 0)
    v["otPay"] = ROUND(v["hourly"] * v["otH"] * 1.5, 0)
    v["gross"] = stale_gross if stale_gross is not None else v["basePay"] + v["dutyAlw"] + v["otPay"] + v["meal"] + v["car"]
    nt = (v["meal"] + v["car"]) if full_nontax else min(v["meal"], S["limit"]) + min(v["car"], S["limit"])
    t = v["gross"] - nt
    v["np"] = ROUNDDOWN(min(max(ROUNDDOWN(t, -3), S["npFloor"]), S["npCap"]) * S["np"], -1)
    v["hi"] = ROUNDDOWN(t * S["hi"], -1)
    v["ltc"] = ROUNDDOWN(v["hi"] * S["ltc"], -1)
    v["ei"] = ROUNDDOWN(t * S["ei"], -1)
    v["tax"] = B.tax_of(t, v["family"])
    v["ltax"] = ROUNDDOWN(v["tax"] * 0.1, -1)
    v["ded"] = v["np"] + v["hi"] + v["ltc"] + v["ei"] + v["tax"] + v["ltax"]
    v["net"] = v["gross"] - v["ded"]
    v["_fb"] = [k for k, x in (("np", min(max(ROUNDDOWN(t, -3), S["npFloor"]), S["npCap"]) * S["np"]), ("hi", t * S["hi"]),
                                ("ltc", v["hi"] * S["ltc"]), ("ei", t * S["ei"]), ("ltax", v["tax"] * 0.1)) if B.float_boundary(x)]
    return v


def formulas(r, st, full_nontax=False):
    R = lambda k: "%s%d" % (L[k], r)  # noqa: E731
    nt = "(%s+%s)" % (R("meal"), R("car")) if full_nontax else "(MIN(%s,%s)+MIN(%s,%s))" % (R("meal"), st.ref("limit"), R("car"), st.ref("limit"))
    t = "(%s-%s)" % (R("gross"), nt)
    return {"hourly": "=ROUNDDOWN((%s+%s)/209,0)" % (R("basePay"), R("dutyAlw")), "otPay": "=ROUND(%s*%s*1.5,0)" % (R("hourly"), R("otH")),
            "gross": "=%s+%s+SUM(%s:%s)" % (R("basePay"), R("dutyAlw"), R("otPay"), R("car")),
            "np": "=ROUNDDOWN(MIN(MAX(ROUNDDOWN(%s,-3),%s),%s)*%s,-1)" % (t, st.ref("npFloor"), st.ref("npCap"), st.ref("np")),
            "hi": "=ROUNDDOWN(%s*%s,-1)" % (t, st.ref("hi")), "ltc": "=ROUNDDOWN(%s*%s,-1)" % (R("hi"), st.ref("ltc")),
            "ei": "=ROUNDDOWN(%s*%s,-1)" % (t, st.ref("ei")), "tax": "=" + B.tax_formula(t, R("family")),
            "ltax": "=ROUNDDOWN(%s*0.1,-1)" % R("tax"), "ded": "=SUM(%s:%s)" % (R("np"), R("ltax")), "net": "=%s-%s" % (R("gross"), R("ded"))}


def write_payroll(path, emps, acc):
    A = Accidents("PEOH-PAY", path.name)
    book = Book(path)
    sh = book.sheet("급여대장")
    sm = book.sheet("법인별집계")
    st = B.Settings2(book, "요율", SETS)
    tax_meta = B.write_tax_sheet(book)
    S = st.val
    sh.put("A", 1, "2026년 12월 그룹 급여대장 (퍼허홀딩스·퍼허푸드·퍼허테크)", "title")
    sh.put("A", 2, "작성: 한결노무법인 (급여 아웃소싱 표준양식 v3) / 지급일 2026-12-25")
    for k, h in COLS:
        sh.put(L[k], 4, h, "head")
    r = 5
    sub_rows, block, vals, row_of, sub_of, first_of, fb = [], {}, {}, {}, {}, {}, []
    cell_val = {}  # 소계·총계·법인별집계 칸 → 저장한 값 (사고가 번진 칸의 실제값)
    for corp, cname, _d, _s in CORPS:
        sh.put("A", r, "■ %s (%s)" % (cname, corp), "totalLabel")
        sub_rows.append(r)
        r += 1
        members = [e for e in emps if e["corp"] == corp and e["status"] == "재직" and e["hireDate"] <= dt.date(2026, 12, 1)]
        if corp == "PF":  # 12/1 전적자가 푸드 블록에도 그대로 (11월 행 복사)
            members.append(acc["transfer"]["prevCorp"])
        members.sort(key=lambda e: (e["dept"], e["empNo"]))
        first = r
        first_of[corp] = r
        for e in members:
            inp = {k: e.get(k) for k, _ in COLS if k in ("corp", "empNo", "name", "dept", "position", "family", "basePay", "dutyAlw", "otH", "car")}
            inp.update(corp=corp, meal=MEAL[corp], memo=None)
            full = e is acc["nontax"]
            stale = None
            if full:
                inp["meal"], inp["memo"] = 300_000, "식대 30만 (전액 비과세 처리)"
            if e is acc["stale"]:
                prev = calc({**inp, "otH": acc["staleOtH"]}, S)
                stale = prev["gross"]
                inp["memo"] = None
            a = calc(inp, S, full_nontax=full, stale_gross=stale)
            i = calc({**inp, "meal": MEAL[corp]} if full else inp, S)
            fs = formulas(r, st, full_nontax=full)
            for k, _ in COLS:
                v = a[k]
                f = fs.get(k)
                if k == "gross" and stale is not None:
                    f = None
                if f:
                    sh.formula(L[k], r, f, v, B.money(v) or ("num" if k in ("otH", "family") else None))
                else:
                    sh.put(L[k], r, v, "text" if k == "empNo" else (B.money(v) if k not in ("otH", "family") else "num"))
            key = (corp, e["empNo"])
            fb += ["%s%d" % (L[k], r) for k in a["_fb"]]
            row_of[key] = r
            vals[key] = (a, i)
            r += 1
        # 소계: 푸드는 블록 첫 행에 행을 끼워 넣은 뒤 SUM 시작 행이 한 칸 밀림
        start = first + 1 if corp == acc["shiftCorp"] else first
        sh.put("C", r, "%s 소계" % cname, "totalLabel")
        for k in SUMMED:
            tot = sum(vals[(corp, e["empNo"])][0][k] for e in members if row_of[(corp, e["empNo"])] >= start)
            sh.formula(L[k], r, "=SUM(%s%d:%s%d)" % (L[k], start, L[k], r - 1), tot, "total" if k != "otH" else "num")
            cell_val["%s%d" % (L[k], r)] = tot
        sub_rows.append(r)
        sub_of[corp] = r
        block[corp] = members
        r += 1
    last = r - 1
    total = r
    sh.put("C", total, "총계", "totalLabel")
    for k in SUMMED:
        tot = sum(vals[(c, e["empNo"])][0][k] for c in block for e in block[c] if row_of[(c, e["empNo"])] >= (first_of[c] + (1 if c == acc["shiftCorp"] else 0)))
        sh.formula(L[k], total, "=" + "+".join("%s%d" % (L[k], sub_of[c]) for c in sub_of), tot, "total" if k != "otH" else "num")
        cell_val["%s%d" % (L[k], total)] = tot
    sh.ws.freeze_panes(4, 3)
    sh.widths({"A": 9, "C": 8})
    # 법인별집계
    sm.put("A", 1, "법인별 12월 급여 집계", "title")
    for c, h in zip("ABCDEF", ["법인코드", "법인명", "인원", "지급계", "공제계", "실지급액"]):
        sm.put(c, 3, h, "head")
    for n, (corp, cname, _d, _s) in enumerate(CORPS):
        rr = 4 + n
        sm.put("A", rr, corp)
        sm.put("B", rr, cname)
        sm.put("C", rr, len(block[corp]))
        cell_val["법인별집계!C%d" % rr] = len(block[corp])
        start = first_of[corp] + (1 if corp == acc["shiftCorp"] else 0)
        for c, k in zip("DEF", ("gross", "ded", "net")):
            v = sum(vals[(corp, e["empNo"])][0][k] for e in block[corp] if row_of[(corp, e["empNo"])] >= start)
            sm.formula(c, rr, "=급여대장!%s%d" % (L[k], sub_of[corp]), v, "won")
            cell_val["법인별집계!%s%d" % (c, rr)] = v
    book.close()

    meta = {"급여대장": {"role": "groupPayrollBlocks", "payMonth": "2026-12", "headerRows": [4], "firstDataRow": 5, "lastDataRow": last,
                      "subtotalRows": sub_rows, "blockHeaderRows": sub_rows[::2], "blockSubtotalRows": sub_rows[1::2], "totalRow": total,
                      "titleRows": [1, 2], "blocks": {c: {"from": first_of[c], "subtotal": sub_of[c]} for c in sub_of},
                      "rowOf": {"%s|%s" % k: v for k, v in row_of.items()}, "floatBoundaryCells": fb},
            "법인별집계": {"role": "corpSummary", "headerRows": [3], "firstDataRow": 4, "lastDataRow": 3 + len(CORPS), "titleRows": [1]},
            "요율": st.meta(), "세액표": tax_meta}
    pfc = acc["shiftCorp"]
    hdr = dict(COLS)
    corp_row = {c: 4 + n for n, (c, *_x) in enumerate(CORPS)}
    summary_col = {"gross": "D", "ded": "E", "net": "F"}

    def in_sub(c, r):
        return r >= first_of[c] + (1 if c == pfc else 0)

    def spread(c, delta, head=0):
        """한 법인 블록의 소계에 더해지는 차이(delta: 키 → 실제−기대)가 번지는 소계·총계·법인별집계 칸 → {칸: 차이}"""
        out = {}
        for k, d in delta.items():
            if d and k in SUMMED:
                out["%s%d" % (L[k], sub_of[c])] = d
                out["%s%d" % (L[k], total)] = d
                if k in summary_col:
                    out["법인별집계!%s%d" % (summary_col[k], corp_row[c])] = d
        if head:
            out["법인별집계!C%d" % corp_row[c]] = head
        return out

    def header_of(cell):
        sheet, ref = cell.split("!") if "!" in cell else ("급여대장", cell)
        col = ref.rstrip("0123456789")
        if sheet == "법인별집계":
            return {"C": "인원", "D": "지급계", "E": "공제계", "F": "실지급액"}[col]
        return next(h for k, h in COLS if L[k] == col)

    def rows_of_cells(cells):
        out = []
        for cell in cells:
            sheet, ref = cell.split("!") if "!" in cell else ("급여대장", cell)
            x = {"sheet": sheet, "rowNo": int(ref.lstrip("ABCDEFGHIJKLMNOPQRSTUVWXYZ"))}
            if x not in out:
                out.append(x)
        return out

    effects = {}  # 사고 순번 → {칸: 실제−기대}
    row_delta = lambda a, i: {k: a[k] - i[k] for k in SUMMED if a[k] != i[k]}  # noqa: E731
    # 1) 소계 범위 밀림: 블록 첫 행이 소계에서 빠짐
    miss = next(e for e in block[pfc] if row_of[(pfc, e["empNo"])] == first_of[pfc])
    ma = vals[(pfc, miss["empNo"])][0]
    eff1 = spread(pfc, {k: -ma[k] for k in SUMMED})
    effects[1] = eff1
    A.add("SUBTOTAL_RANGE_SHIFTED", "급여대장", "%s 소계 SUM이 블록 둘째 행부터 (=SUM(%s%d:…)) — 첫 행 %s(%s) 지급계 %s원이 소계·총계·법인별집계에서 빠짐 (블록 맨 위에 행을 끼운 뒤 범위 미수정). "
          "소계 행의 다른 합계 열도 같은 범위라 함께 빠짐 (cascade)"
          % (CORP_NAME[pfc], L["gross"], first_of[pfc] + 1, miss["name"], miss["empNo"], format(ma["gross"], ",")),
          ["TOTAL_ROW_SKIP", "RECOMPUTE_CHECK", "FORMULA_READ"], corp=pfc, empNo=miss["empNo"],
          cells=["%s%d" % (L["gross"], sub_of[pfc])], cascade=[c for c in eff1 if c != "%s%d" % (L["gross"], sub_of[pfc])],
          affected=[{"sheet": "급여대장", "rowNo": first_of[pfc]}] + rows_of_cells(eff1),
          formula={"actual": "=SUM(%s%d:%s%d)" % (L["gross"], first_of[pfc] + 1, L["gross"], sub_of[pfc] - 1),
                   "expected": "=SUM(%s%d:%s%d)" % (L["gross"], first_of[pfc], L["gross"], sub_of[pfc] - 1)},
          values={"지급계": {"actual": cell_val["%s%d" % (L["gross"], sub_of[pfc])], "expected": cell_val["%s%d" % (L["gross"], sub_of[pfc])] + ma["gross"]}},
          cascadeValues={c: {"header": header_of(c), "actual": cell_val[c], "expected": cell_val[c] - d} for c, d in eff1.items()})
    # 2) 전적자 이중 지급: 푸드 블록의 예전 행 전체가 없어야 함
    t = acc["transfer"]
    old = t["prevCorp"]
    r_old, r_new = row_of[("PF", old["empNo"])], row_of[("PT", t["empNo"])]
    oa = vals[("PF", old["empNo"])][0]
    eff2 = spread("PF", {k: oa[k] for k in SUMMED} if in_sub("PF", r_old) else {}, head=1)
    effects[2] = {**{"%s%d" % (L[k], r_old): oa[k] for k in SUMMED if oa[k]}, **eff2}
    A.add("TRANSFER_DOUBLE_PAY", "급여대장", "12/1 퍼허푸드 → 퍼허테크 전적자가 두 법인 블록에 모두 있어 12월 급여 이중 (푸드 사번 %s, 테크 사번 %s — 사번이 달라 키로는 안 보임, 성명·생년월일로 찾아야 함). "
          "푸드 소계·총계·법인별집계(인원·지급계·공제계·실지급액)에도 이 행이 더해짐 (cascade)"
          % (old["empNo"], t["empNo"]), ["DUPLICATE_DETECT", "CROSS_FILE_MATCH", "NAME_MATCH"], corp="PF", empNo=old["empNo"], rowNo=r_old,
          cells=["%s%d" % (L["gross"], r_old)], cascade=list(eff2), affected=[{"sheet": "급여대장", "rowNo": r_old}, {"sheet": "급여대장", "rowNo": r_new}] + rows_of_cells(eff2),
          values={"지급계": {"actual": oa["gross"], "expected": 0}},
          cascadeValues={c: {"header": header_of(c), "actual": cell_val[c], "expected": cell_val[c] - d} for c, d in eff2.items()})
    # 3) 식대 전액 비과세: 그 행 보험료·세금 + 테크 소계·총계·집계(공제계·실지급액)
    e = acc["nontax"]
    a = vals[(e["corp"], e["empNo"])][0]
    i = calc({**{k: a[k] for k in a}}, S)
    rr = row_of[(e["corp"], e["empNo"])]
    d3 = row_delta(a, i)
    eff3 = spread(e["corp"], d3)
    effects[3] = {**{"%s%d" % (L[k], rr): d for k, d in d3.items()}, **eff3}
    A.add("NONTAX_OVER_LIMIT", "급여대장", "식대 30만원을 전액 비과세로 (이 행만 과세 기준 수식이 지급계−식대−자가운전) — 한도 20만 초과분 10만원이 보험료·소득세 기준에서 빠짐. "
          "공제계·실지급액이 틀려 %s 소계·총계·법인별집계에도 번짐 (cascade)" % CORP_NAME[e["corp"]],
          ["LEGAL_RULE_COMPARE", "FORMULA_READ", "RECOMPUTE_CHECK"], legal=True, corp=e["corp"], empNo=e["empNo"], rowNo=rr,
          cells=["%s%d" % (L["meal"], rr)] + ["%s%d" % (L[k], rr) for k in d3], cascade=list(eff3),
          affected=[{"sheet": "급여대장", "rowNo": rr}] + rows_of_cells(eff3),
          values={hdr[k]: {"actual": a[k], "expected": i[k]} for k in d3},
          cascadeValues={c: {"header": header_of(c), "actual": cell_val[c], "expected": cell_val[c] - d} for c, d in eff3.items()})
    # 4) 지급계 숫자 고정 (11월 금액): 그 행 보험료·세금·실지급 + 테크 소계·총계·집계
    e = acc["stale"]
    a, i = vals[(e["corp"], e["empNo"])]
    rr = row_of[(e["corp"], e["empNo"])]
    d4 = row_delta(a, i)
    eff4 = spread(e["corp"], d4)
    effects[4] = {**{"%s%d" % (L[k], rr): d for k, d in d4.items()}, **eff4}
    A.add("HARDCODED_STALE", "급여대장", "지급계 칸이 수식 대신 숫자 (11월 연장 %s시간 기준 금액 그대로) — 12월 연장수당 %s원과 안 맞음. "
          "지급계를 기준으로 계산하는 보험료·소득세·공제계·실지급액과 %s 소계·총계·법인별집계도 함께 틀림 (cascade)"
          % (acc["staleOtH"], format(a["otPay"], ","), CORP_NAME[e["corp"]]),
          ["HARDCODED_IN_FORMULA_COLUMN", "RECOMPUTE_CHECK"], corp=e["corp"], empNo=e["empNo"], rowNo=rr,
          cells=["%s%d" % (L[k], rr) for k in d4], cascade=list(eff4),
          affected=[{"sheet": "급여대장", "rowNo": rr}] + rows_of_cells(eff4),
          values={hdr[k]: {"actual": a[k], "expected": i[k]} for k in d4},
          cascadeValues={c: {"header": header_of(c), "actual": cell_val[c], "expected": cell_val[c] - d} for c, d in eff4.items()})
    # 같은 칸을 바꾸는 사고끼리: 한 사고의 기대값은 그 사고만 고친 값. trueValue = 그 칸의 사고를 모두 고친 값
    for n, x in enumerate(A.items, 1):
        ov = []
        for m, y in enumerate(A.items, 1):
            shared = sorted(set(effects[n]) & set(effects[m])) if m != n else []
            if shared:
                ov.append({"with": y["id"], "cells": shared,
                           "note": "두 사고가 같은 칸을 바꿈. values·cascadeValues의 expected는 이 사고만 고친 값이라 다른 사고 몫이 남아 있음",
                           "trueValue": {c: cell_val[c] - sum(effects[k].get(c, 0) for k in effects) for c in shared if c in cell_val}})
        if ov:
            x["overlaps"] = ov
    t_corp = t["corp"]
    clean_blocks = [c for c, *_x in CORPS if c not in (pfc, "PF", acc["nontax"]["corp"], acc["stale"]["corp"], t_corp)]
    return {"file": path.name, "kind": "PAYROLL", "closestEntity": "PAY_RESULT_HISTORY", "supported": False,
            "sheetOrder": ["급여대장", "법인별집계", "요율", "세액표"], "sheets": meta,
            "expectedMapping": {"groupPayrollBlocks": {h: k for k, h in COLS},
                                "corpSummary": {"법인코드": "corp", "법인명": "corpName", "인원": "headcount", "지급계": "gross", "공제계": "ded", "실지급액": "net"}},
            "key": "법인코드 + 사번 (사번은 법인마다 0001부터라 법인끼리 겹침)",
            "rules": {"ordinaryHourlyWage": {"expr": "floor((basePay+dutyAllowance)/209)"}, "overtime": "ROUND(통상시급 × 연장 × 1.5)",
                      "meal": {c: MEAL[c] for c in MEAL}, "nonTaxable": "식대·자가운전 각각 MIN(금액, 20만)", "insuranceRounding": "floor10",
                      "blocks": "법인마다 '■ 법인명 (코드)' 머리 행 → 직원(부서·사번순) → 소계, 맨 아래 총계 = 소계의 합"},
            "accidents": A.items, "cleanBlocks": clean_blocks,
            "legitUnusual": ["같은 사번(0001 등)이 법인마다 다른 사람 (법인코드와 함께 키)", "법인마다 식대 금액이 다름 (푸드 15만)",
                             "지급일이 12/25 (공휴일 전 지급, 노무법인 양식에 적힌 날짜)"],
            "valueNoise": [{"type": "BLOCK_LAYOUT", "detail": "한 시트에 법인 블록 3개, 블록마다 머리 행·소계 행 (subtotalRows에 둘 다 있음)"}],
            "crossFile": {"roster": {"file": "직원명부.xlsx", "detail": "법인코드+사번, 전적자는 성명·생년월일로"}}}


# ================================================================ 연차대장 (법인마다 규칙이 다름)

def granted(e, corp, use_corp_hire=False):
    d = e["hireDate"] if use_corp_hire else e["groupHire"]
    if corp == "PH":  # 회계연도(1/1) 기준
        if d.year >= 2026:
            return min(datedif_m(d, BASE), 11)
        if d.year == 2025:
            return ROUND(15 * ((dt.date(2025, 12, 31) - d).days + 1) / 365, 1)
        return min(15 + (2026 - d.year - 1) // 2, 25)
    n = datedif_y(d, BASE)
    if n < 1:
        return min(datedif_m(d, BASE), 11)
    return 15 + (n - 1) // 2 if corp == "PF" else min(15 + (n - 1) // 2, 25)


def leave_formula(corp, r, col):
    d = "%s%d" % (col, r)
    if corp == "PH":
        return ("=IF(YEAR(%s)>=2026,MIN(DATEDIF(%s,DATE(2026,12,31),\"M\"),11),IF(YEAR(%s)=2025,ROUND(15*(DATE(2025,12,31)-%s+1)/365,1),"
                "MIN(15+INT((2026-YEAR(%s)-1)/2),25)))" % (d, d, d, d, d))
    cap = ",25)" if corp == "PT" else ")"
    return "=IF(E%d<1,MIN(DATEDIF(%s,DATE(2026,12,31),\"M\"),11),%s15+INT((E%d-1)/2)%s)" % (r, d, "MIN(" if corp == "PT" else "", r, cap if corp == "PT" else "")


def write_leave(path, emps, acc, rng):
    A = Accidents("PEOH-LV", path.name)
    book = Book(path)
    meta, dev_rows = {}, []
    for corp, cname, _d, _s in CORPS:
        sh = book.sheet(cname)
        sh.put("A", 1, "%s 2026 연차대장 (%s)" % (cname, "회계연도 기준" if corp == "PH" else "입사일 기준"), "title")
        for c, h in zip("ABCDEFGH", ["사번", "성명", "그룹입사일", "입사일", "근속(년)", "발생", "사용", "잔여"]):
            sh.put(c, 3, h, "head")
        members = sorted([e for e in emps if e["corp"] == corp and e["status"] == "재직"], key=lambda e: e["empNo"])
        r, row_of = 4, {}
        for e in members:
            wrong = e is acc["seniority"]
            g = granted(e, corp, use_corp_hire=wrong)
            gi = granted(e, corp)
            n = datedif_y(e["hireDate"] if wrong else e["groupHire"], BASE)
            used = acc["overUse"][1] if e is acc["overUse"][0] else min(gi, rng.randint(0, int(gi * 2)) / 2)
            used = int(used) if float(used).is_integer() else used
            sh.put("A", r, e["empNo"], "text")
            sh.put("B", r, e["name"])
            sh.put("C", r, e["groupHire"], "date")
            sh.put("D", r, e["hireDate"], "date")
            sh.formula("E", r, '=DATEDIF(%s%d,DATE(2026,12,31),"Y")' % ("D" if wrong else "C", r), n)
            sh.formula("F", r, leave_formula(corp, r, "D" if wrong else "C"), g, "num")
            sh.put("G", r, used, "num")
            sh.formula("H", r, "=F%d-G%d" % (r, r), g - used, "num")
            row_of[e["empNo"]] = r
            if corp == "PF" and gi > 25:
                dev_rows.append(r)
            if wrong:
                acc["_sen"] = (cname, r, g, gi, used)
            if e is acc["overUse"][0]:
                acc["_over"] = (cname, r, g, used)
            r += 1
        sh.widths({"C": 11, "D": 11})
        meta[cname] = {"role": "leaveLedger", "corp": corp, "headerRows": [3], "firstDataRow": 4, "lastDataRow": r - 1, "titleRows": [1], "rowOf": row_of}
    book.close()
    cname, r, g, gi, used = acc["_sen"]
    e = acc["seniority"]
    A.add("SENIORITY_NOT_CARRIED", cname, "2024-07-01 퍼허푸드에서 전적한 직원 행만 근속·발생 수식이 입사일(D열, 전적일)을 봄 → 그룹 규정(전적 시 근속 승계)대로 그룹입사일(C열)이면 %s일인데 %s일"
          % (gi, g), ["FORMULA_READ", "CROSS_FIELD_RULE"], corp=e["corp"], empNo=e["empNo"], rowNo=r,
          cells=["E%d" % r, "F%d" % r], values={"발생": {"actual": g, "expected": gi}}, formula={"actual": "D%d" % r, "expected": "C%d" % r})
    cname, r, g, used = acc["_over"]
    e = acc["overUse"][0]
    A.add("NEGATIVE_REMAIN", cname, "2026년 입사자가 회계연도 기준 발생(%s일)보다 많이 사용(%s일) → 잔여 %s (입사일 기준 월 단위 발생을 미리 당겨 씀)" % (g, used, g - used),
          ["RANGE_CHECK", "CROSS_FIELD_RULE"], corp=e["corp"], empNo=e["empNo"], rowNo=r, cells=["H%d" % r], values={"잔여": {"actual": g - used}})
    devs = [{"rule": "annualLeaveCap", "sheet": "퍼허푸드", "company": "15+INT((근속−1)/2) 상한 없음", "statutory": "최대 25일 (제60조 제4항)",
             "rows": dev_rows},
            {"rule": "annualLeaveFiscalYear", "sheet": "퍼허홀딩스", "company": "회계연도 기준 (전년 입사자는 15×근무일/365)",
             "statutory": "입사일 기준 (퇴직 시 근로자에게 불리하지 않게 정산해야 함)"}]
    return {"file": path.name, "kind": "LEAVE", "closestEntity": "LEAVE_BALANCE", "supported": False,
            "sheetOrder": [c[1] for c in CORPS], "sheets": meta,
            "expectedMapping": {"leaveLedger": {"사번": "empNo", "성명": "name", "그룹입사일": "groupHireDate", "입사일": "hireDate", "근속(년)": "years",
                                                "발생": "granted", "사용": "used", "잔여": "remain"}},
            "rules": {"PH": "회계연도: 2026 입사 MIN(개월,11), 2025 입사 ROUND(15×근무일/365,1), 그 전 MIN(15+INT((2026−입사연도−1)/2),25)",
                      "PF": "입사일: 1년 미만 MIN(개월,11), 이후 15+INT((근속−1)/2) (상한 없음)", "PT": "입사일: 법정 그대로 (25일 상한)",
                      "seniority": "그룹 규정: 계열사 전적 시 근속 승계 → 그룹입사일 기준 (가정)", "remain": "발생 − 사용"},
            "legalDeviations": devs, "accidents": A.items, "cleanSheets": ["퍼허푸드"],
            "legitUnusual": ["법인마다 발생 규칙이 다름 (회계연도/입사일)", "전적자는 입사일 ≠ 그룹입사일 (정상)"]}


# ================================================================ 직원명부·조직

ROSTER_COLS = [("_corp", "법인코드"), ("_corpName", "법인명"), ("empNo", "사번"), ("name", "성명"), ("dept", "부서"), ("position", "직위"),
               ("duty", "직책"), ("empType", "고용형태"), ("_groupHire", "그룹입사일"), ("hireDate", "입사일"), ("birthDate", "생년월일"),
               ("mobile", "휴대폰"), ("email", "이메일"), ("status", "재직구분"), ("resignDate", "퇴사일"), ("_memo", "비고")]


def write_roster(cdir, emps, acc, seed):
    rng = B.rng_for(CID, "roster", seed)
    errs = B.RosterErrors()
    rows = []
    for e in emps:
        memo = ("%s로 전적" % CORP_NAME[e["transferTo"]]) if e.get("transferTo") else ("%s에서 전적" % CORP_NAME[e["prevCorp"]["corp"]] if e.get("prevCorp") else None)
        rows.append({"_corp": e["corp"], "_corpName": CORP_NAME[e["corp"]], "empNo": e["empNo"], "name": e["name"], "dept": e["dept"],
                     "position": e["position"], "duty": e["duty"], "empType": e["empType"], "_groupHire": e["groupHire"], "hireDate": e["hireDate"],
                     "birthDate": e["birthDate"], "mobile": e["mobile"], "email": e["email"], "status": e["status"], "resignDate": e["resignDate"],
                     "_memo": memo, "_e": e})
    idx = list(range(len(rows)))
    rng.shuffle(idx)
    reserved = {id(x) for x in acc["reserved"]}
    take = lambda k, cond=lambda r: True: [idx.pop(idx.index(i)) for i in [j for j in idx if cond(rows[j]) and id(rows[j]["_e"]) not in reserved][:k]]  # noqa: E731
    for i in take(2, lambda r: r["status"] == "재직"):
        rows[i]["hireDate"] = None
        errs.add(i, "hireDate", "REQUIRED", "입사일 빈 칸")
    for i in take(1):
        rows[i]["email"] = rows[i]["email"].replace(".co.kr", "")
        errs.add(i, "email", "INVALID_EMAIL", "이메일 도메인 끝 없음: %s" % rows[i]["email"])
    i, j = take(2, lambda r: r["_corp"] == "PF" and r["status"] == "재직")
    rows[j]["empNo"] = rows[i]["empNo"]
    errs.add(i, "empNo", "DUPLICATE_KEY", "같은 법인(PF) 안에서 사번 중복 (다른 사람)")
    errs.add(j, "empNo", "DUPLICATE_KEY", "같은 법인(PF) 안에서 사번 중복 — 이 행의 실제 사번은 %s" % rows[j]["_e"]["empNo"])
    for i in take(1, lambda r: r["empType"] == "계약직"):
        rows[i]["empType"] = "파견"
        errs.add(i, "empType", "INVALID_ENUM", "고용형태 '파견' (그룹 코드표: 정규직·계약직, 파견은 사용사업주 명부 대상 아님)")
    # 퇴사일 < 입사일: 실제 퇴사자(2025-12-31 퇴사, 재직구분 '퇴사'는 맞음)의 퇴사일만 잘못 적음 — 급여·연차 파일과는 어긋나지 않는다
    i = next(n for n, r in enumerate(rows) if r["status"] == "퇴사" and not r["_e"].get("transferTo"))
    idx.remove(i)
    true_resign = rows[i]["resignDate"]
    rows[i]["resignDate"] = rows[i]["hireDate"] - dt.timedelta(days=rng.randint(30, 300))
    errs.add(i, "resignDate", "DATE_ORDER", "퇴사일 < 입사일 (실제 퇴사일 %s)" % true_resign.isoformat())
    for i in take(1, lambda r: r["status"] == "재직" and r["hireDate"] and r["_groupHire"] == r["hireDate"]):
        rows[i]["_groupHire"] = rows[i]["hireDate"] + dt.timedelta(days=rng.randint(200, 900))
        errs.add(i, "그룹입사일", "GROUP_HIRE_AFTER_CORP_HIRE", "그룹입사일이 법인 입사일보다 늦음 (그룹입사일 ≤ 입사일이어야 함)", detectable=False)
    # 12/1 전적자의 푸드 행: 명부에서는 퇴사(전적) 처리돼 정상. 2024 전적자 푸드 행은 '재직'으로 남은 오류
    old24 = next(n for n, r in enumerate(rows) if r["_e"].get("transferTo") and r["_e"]["resignDate"] == dt.date(2024, 6, 30))
    new24 = next(n for n, r in enumerate(rows) if r["_e"] is acc["seniority"])
    rows[old24].update(status="재직", resignDate=None)
    errs.add(old24, "name", "DUPLICATE_SUSPECTED", "2024-07-01 퍼허테크로 전적한 사람의 퍼허푸드 행이 퇴사 처리 안 됨 (같은 성명·생년월일)")
    errs.add(new24, "name", "DUPLICATE_SUSPECTED", "같은 사람이 퍼허푸드 사번 %s로도 재직" % rows[old24]["empNo"])
    for r in rows:
        for f in ("hireDate", "birthDate", "resignDate", "_groupHire"):
            if r[f] is not None:
                r[f] = B.fmt_date(r[f], B.date_style(rng, "cell", ["yy"], 0.05))
    B.date_format_errors(rows, ("hireDate", "birthDate", "resignDate"), errs)
    head_rows, first = B.write_roster_xlsx(
        cdir / "직원명부.xlsx", "통합명부", ROSTER_COLS, rows,
        title_rows=["퍼허그룹 계열사 통합 인사명부", "작성: 한결노무법인 / 기준일 2026-12-20"],
        groups=[("_corp", "_corpName", "법인"), ("empNo", "empType", "인적사항·소속"), ("_groupHire", "birthDate", "일자"),
                ("mobile", "email", "연락처"), ("status", "_memo", "재직")],
        widths={"_corpName": 11, "name": 8, "email": 26, "mobile": 14})
    legit = [first + n for n, r in enumerate(rows) if r["_e"].get("transferTo") and r["status"] != "재직"]
    return {"mapping": {h: (None if f.startswith("_") else f) for f, h in ROSTER_COLS}, "errors": errs.rows(first), "rows": len(rows),
            "layout": {"sheet": "통합명부", "titleRows": [1, 2], "headerRows": head_rows, "firstDataRow": first, "key": "법인코드 + 사번",
                       "notes": ["세 법인이 한 시트, 사번은 법인마다 0001부터 → 법인이 다르면 같은 사번도 다른 사람 (DUPLICATE_KEY 아님)",
                                 "그룹입사일(매핑 없음)과 입사일(법인 입사일)이 따로", "생산직도 그룹 이메일 있음"],
                       "legitUnusual": [{"rowNo": r, "detail": "계열사 전적으로 퇴사(전적) 처리된 예전 법인 행 — 같은 사람이 다른 법인에 재직 (정상 이력)"} for r in legit]}}


def write_org(cdir):
    org = [{"deptCode": "G000", "name": NAME, "parentDeptCode": None}]
    for n, (corp, cname, depts, _s) in enumerate(CORPS, 1):
        org.append({"deptCode": corp + "00", "name": cname, "parentDeptCode": "G000"})
        org += [{"deptCode": "%s%02d" % (corp, m), "name": d, "parentDeptCode": corp + "00"} for m, d in enumerate(depts, 1)]
    cols = [("deptCode", "조직코드"), ("name", "조직명"), ("parentDeptCode", "상위조직코드")]
    B.write_org(cdir / "조직.csv", org, cols, "utf-8-sig")
    return org, cols


def build(df, used, out_dir, seed):
    company = {"id": CID, "name": NAME, "industry": "그룹(지주·식품·IT)",
               "profile": "계열사 3개 법인 160명을 노무법인이 한 파일로 관리, 법인마다 사번이 겹침(법인코드+사번 키), 법인 블록·소계가 끼인 급여대장, 법인마다 다른 연차 규칙, 계열사 전적"}
    cdir = out_dir / CID
    cdir.mkdir(parents=True, exist_ok=True)
    emps = people(df, used, seed)
    rng = B.rng_for(CID, "plan", seed)
    tr12 = next(e for e in emps if e.get("prevCorp") and e["hireDate"] == dt.date(2026, 12, 1))
    sen = next(e for e in emps if e.get("prevCorp") and e["hireDate"] == dt.date(2024, 7, 1))
    pt = sorted([e for e in emps if e["corp"] == "PT" and e["status"] == "재직" and e not in (tr12, sen)], key=lambda e: e["empNo"])
    nontax, stale = rng.sample([e for e in pt if e["otH"]], 2)
    new_ph = [e for e in emps if e["corp"] == "PH" and e["status"] == "재직" and e["hireDate"].year == 2026]
    if not new_ph:
        e = sorted([e for e in emps if e["corp"] == "PH" and e["status"] == "재직" and e["position"] == "사원"], key=lambda e: e["empNo"])[-1]
        e["hireDate"] = e["groupHire"] = dt.date(2026, 3, 2)
        new_ph = [e]
    over = new_ph[0]
    acc_p = {"shiftCorp": "PF", "transfer": tr12, "nontax": nontax, "stale": stale, "staleOtH": max(0, stale["otH"] - 4)}
    pay_key = write_payroll(cdir / PAY_FILE, emps, acc_p)
    acc_l = {"seniority": sen, "overUse": (over, granted(over, "PH") + 3)}
    leave_key = write_leave(cdir / LEAVE_FILE, emps, acc_l, B.rng_for(CID, "leave", seed))
    retired = [e for e in emps if e["status"] == "퇴사"]
    roster = write_roster(cdir, emps, {"seniority": sen, "reserved": [tr12, tr12["prevCorp"], sen, sen["prevCorp"], nontax, stale, over, *retired]}, seed)
    org, org_cols = write_org(cdir)
    return B.answer_key(company, "직원명부.xlsx", "조직.csv", roster["mapping"], org_cols, roster["errors"],
                        {"employees": sum(1 for e in emps if not e.get("transferTo")), "rosterRows": roster["rows"], "orgs": len(org),
                         "corporations": len(CORPS)}, {PAY_FILE: pay_key, LEAVE_FILE: leave_key}, {"rosterLayout": roster["layout"]})
