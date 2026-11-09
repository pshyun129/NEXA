"""
인사발령대장.xlsx — 입사·승진·전보·보직·퇴사 이력 (수식 없음).

마지막 발령 상태 = 직원명부의 부서·직위 (오류를 넣기 전 기준). 직위는 나이 기준 사다리(사원→대리→과장→차장→부장),
승진은 해당 나이가 되는 해 1월 1일 정기 승진. 일부 부서는 예전 이름이 있어 오래된 발령에는 옛 부서명이 나온다.
"""
import datetime as dt

from .common import Accidents, clean
from .facts import CUTOVER

LADDER = [(29, "사원"), (34, "대리"), (40, "과장"), (46, "차장"), (99, "부장")]
RENAMED = {"플랫폼개발팀": ("개발팀", dt.date(2022, 1, 1)), "고객지원팀": ("CS팀", dt.date(2021, 7, 1)),
           "생산1팀": ("제조팀", dt.date(2020, 3, 1)), "재무팀": ("경리팀", dt.date(2019, 1, 1))}
ACTIONS = {
    "ganatech": {"hire": "입사", "promo": "승진", "move": "전보", "lead": "보직", "quit": "퇴사"},
    "daramulsan": {"hire": "신규입사", "promo": "승진", "move": "전배", "lead": "직책임명", "quit": "퇴직"},
    "mabaservice": {"hire": "HIRE", "promo": "PROMOTION", "move": "TRANSFER", "lead": "APPOINT", "quit": "RESIGN"},
}


def _pos_idx(age):
    return next(i for i, (limit, _) in enumerate(LADDER) if age <= limit)


def _dept_name(team, on):
    old = RENAMED.get(team)
    return old[0] if old and on < old[1] else team


def _events(e, teams, rng):
    hire, birth = e["hireDate"], e["birthDate"]
    cur = [n for _, n in LADDER].index(e["position"])
    start = _pos_idx(hire.year - birth.year)
    ev = []
    pos = LADDER[min(start, cur)][1]
    # 전보: 0~2번, 마지막 부서 = 지금 부서
    n_moves = rng.choices([0, 1, 2], [55, 33, 12])[0] if (CUTOVER - hire).days > 400 else 0
    chain = [e["dept"]]
    for _ in range(n_moves):  # 뒤에서부터: 바로 다음 부서와 다른 부서
        chain.insert(0, rng.choice([t for t in teams if t != chain[0]]))
    move_dates = sorted(hire + dt.timedelta(days=rng.randint(200, (CUTOVER - hire).days - 30)) for _ in range(n_moves))
    ev.append({"kind": "hire", "date": hire, "fromDept": None, "toDept": chain[0], "fromPos": None, "toPos": pos})
    for i in range(min(start, cur) + 1, cur + 1):
        d = dt.date(birth.year + LADDER[i - 1][0] + 1, 1, 1)
        if d <= hire:
            continue
        ev.append({"kind": "promo", "date": d, "toPos": LADDER[i][1]})
    for d, to in zip(move_dates, chain[1:]):
        ev.append({"kind": "move", "date": d, "toDept": to})
    if e["duty"] == "팀장":
        ev.append({"kind": "lead", "date": max(hire, dt.date(2024, 1, 1)) if hire < dt.date(2024, 1, 1) else hire + dt.timedelta(days=90),
                   "duty": "팀장"})
    if e["resignDate"]:
        ev.append({"kind": "quit", "date": e["resignDate"]})
    ev.sort(key=lambda x: (x["date"], ["hire", "promo", "move", "lead", "quit"].index(x["kind"])))
    # 발령 전·후 상태를 채운다
    dept, p = None, None
    for x in ev:
        x["fromDept"], x["fromPos"] = dept, p
        dept = x.get("toDept") or dept
        p = x.get("toPos") or p
        x["toDept"], x["toPos"] = dept, p
    return ev


def build(company, roster, out_dir, rng):
    cid = company["id"]
    teams = sorted({e["dept"] for e in roster})
    A = Accidents("%s-AP" % cid[:4].upper(), "인사발령대장.xlsx")
    rows = []
    for e in roster:
        for x in _events(e, teams, rng):
            rows.append({"emp": e, **x})

    # 사고
    by_emp = lambda emp: [x for x in rows if x["emp"] is emp]  # noqa: E731
    cand = [x for x in rows if x["kind"] in ("promo", "move")]
    before = rng.choice(cand)
    before["date"], before["origDate"] = before["emp"]["hireDate"] - dt.timedelta(days=rng.randint(10, 60)), before["date"]
    movers = [x for x in rows if x["kind"] == "move" and x["emp"] is not before["emp"]]
    same = rng.choice(movers)
    dup = {**same, "toDept": rng.choice([t for t in teams if t not in (same["toDept"], same["fromDept"])]), "dup": True}
    rows.append(dup)
    quitters = [e for e in roster if e["resignDate"] and e["resignDate"] < dt.date(2026, 11, 1)
                and e is not before["emp"] and e is not same["emp"]]
    after_e = rng.choice(quitters)
    after = {"emp": after_e, "kind": "move", "date": after_e["resignDate"] + dt.timedelta(days=rng.randint(5, 40)),
             "fromDept": after_e["dept"], "toDept": rng.choice([t for t in teams if t != after_e["dept"]]),
             "fromPos": after_e["position"], "toPos": after_e["position"], "afterQuit": True}
    rows.append(after)
    promos = [x for x in rows if x["kind"] == "promo" and x["emp"] not in (before["emp"], same["emp"], after_e)
              and x is max(by_emp(x["emp"]), key=lambda y: y["date"] if y["kind"] == "promo" else dt.date.min)]
    lost = rng.choice(promos)
    rows.remove(lost)

    order = {"ganatech": lambda x: (x["date"], x["emp"]["empNo"]), "daramulsan": lambda x: (x["emp"]["empNo"], x["date"]),
             "mabaservice": lambda x: (x["date"], x["emp"]["empNo"])}[cid]
    rows.sort(key=lambda x: (order(x), bool(x.get("dup"))))
    acts = ACTIONS[cid]

    from .xl import Book
    path = out_dir / "인사발령대장.xlsx"
    book = Book(path)
    sh = book.sheet({"ganatech": "발령대장", "daramulsan": "인사발령", "mabaservice": "PERSONNEL_ORDERS"}[cid])
    if cid == "ganatech":
        heads = ["발령번호", "발령일", "사번", "성명", "발령구분", "발령전 부서", "발령후 부서", "발령전 직위", "발령후 직위", "비고"]
        fields = ["orderNo", "effectiveDate", "empNo", "name", "action", "fromDept", "toDept", "fromPosition", "toPosition", "memo"]
        sh.put("A", 1, "인사발령대장 (2010년~)", "title")
        hr = 3
    elif cid == "daramulsan":
        heads = ["사번", "성명", "발령일자", "구분", "부서", "직위", "직책", "비고"]
        fields = ["empNo", "name", "effectiveDate", "action", "toDept", "toPosition", "duty", "memo"]
        hr = 1
    else:
        heads = ["ORDER_NO", "EFFECTIVE_DATE", "EMP_NO", "NAME", "ACTION", "FROM_DEPT", "TO_DEPT", "FROM_POS", "TO_POS"]
        fields = ["orderNo", "effectiveDate", "empNo", "name", "action", "fromDept", "toDept", "fromPosition", "toPosition"]
        hr = 1
    for i, h in enumerate(heads):
        sh.put(chr(65 + i), hr, h, "head")
    textdate = 0
    for n, x in enumerate(rows):
        r = hr + 1 + n
        x["row"] = r
        e = x["emp"]
        d = x["date"]
        vals = {
            "orderNo": ("%d-%03d" % (d.year, n + 1)) if cid == "ganatech" else "PO%d%04d" % (d.year, n + 1),
            "effectiveDate": d, "empNo": int(e["empNo"]) if cid == "daramulsan" else e["empNo"], "name": e["name"],
            "action": acts[x["kind"]], "fromDept": _dept_name(x["fromDept"], d) if x["fromDept"] else None,
            "toDept": _dept_name(x["toDept"], d), "fromPosition": x["fromPos"], "toPosition": x["toPos"],
            "duty": "팀장" if x["kind"] == "lead" else None, "memo": None,
        }
        if cid == "daramulsan":
            vals["effectiveDate"] = d.strftime("%Y%m%d")  # 문자 날짜 (회사 양식)
        elif cid == "ganatech" and rng.random() < 0.1:
            vals["effectiveDate"] = "%d.%d.%d" % (d.year, d.month, d.day)
            textdate += 1
        if x.get("dup"):
            vals["memo"] = "정정 발령" if cid != "mabaservice" else None
        for i, f in enumerate(fields):
            v = vals[f]
            sh.put(chr(65 + i), r, v, "text" if f == "empNo" and isinstance(v, str) else None)
    book.close()

    A.add("ORDER_BEFORE_HIRE", sh.name, "발령일이 입사일보다 빠름 (원래 %s). 날짜순으로 보면 이 사람의 마지막 상태도 달라질 수 있음" % before["origDate"], ["CROSS_FIELD_RULE", "CROSS_FILE_MATCH"],
          empNo=before["emp"]["empNo"], rowNo=before["row"], values={"발령일": before["date"], "입사일": before["emp"]["hireDate"]})
    A.add("SAME_DAY_DUPLICATE", sh.name, "같은 날 같은 사람에게 서로 다른 부서로 전보 2건", ["DUPLICATE_DETECT", "CROSS_FIELD_RULE"],
          empNo=same["emp"]["empNo"], rows=[same["row"], dup["row"]], date=same["date"])
    A.add("ORDER_AFTER_RESIGN", sh.name, "퇴사일(%s) 뒤에 전보 발령" % after_e["resignDate"], ["CROSS_FIELD_RULE", "CROSS_FILE_MATCH"],
          empNo=after_e["empNo"], rowNo=after["row"])
    A.add("FINAL_STATE_MISMATCH", sh.name, "마지막 승진(%s, %s) 발령이 빠져 발령대장 마지막 직위 ≠ 직원명부 직위" % (lost["date"], lost["toPos"]),
          ["CROSS_FILE_MATCH"], empNo=lost["emp"]["empNo"], missingOrder={"date": lost["date"], "toPosition": lost["toPos"]})

    final = {}
    for e in roster:
        final[e["empNo"]] = {"dept": e["dept"], "position": e["position"], "duty": e["duty"], "status": e["status"]}
    noise = [{"type": "OLD_DEPT_NAME", "detail": "부서 이름 변경 전 발령에는 옛 이름", "renamed": {new: {"old": old, "since": since} for new, (old, since) in RENAMED.items() if new in teams}},
             {"type": "ACTION_VOCABULARY", "values": acts, "detail": "발령 구분 용어가 회사마다 다름"}]
    if cid == "daramulsan":
        noise.append({"type": "DATE_AS_TEXT", "format": "yyyyMMdd", "column": "발령일자"})
        noise.append({"type": "AFTER_STATE_ONLY", "detail": "발령 전 상태 없이 발령 후 부서·직위만 기록 → 이전 상태는 앞 행에서 추론해야 함"})
    if textdate:
        noise.append({"type": "DATE_AS_TEXT", "format": "yyyy.M.d", "column": "발령일", "count": textdate, "detail": "날짜 셀과 문자 날짜 혼재"})
    return clean({
        "file": path.name, "kind": "PERSONNEL_ORDER", "closestEntity": None, "supported": False,
        "sheetOrder": [sh.name], "sheets": {sh.name: {"role": "personnelOrders", "headerRows": [hr], "firstDataRow": hr + 1, "lastDataRow": hr + len(rows)}},
        "expectedMapping": {"personnelOrders": dict(zip(heads, fields))},
        "rules": {"promotion": "나이 기준 직위 사다리, 해당 나이가 되는 해 1월 1일 정기 승진", "actions": acts},
        "finalState": final,
        "crossFile": {"roster": {"file": "직원명부", "check": "사번별 마지막 발령의 부서·직위 = 직원명부 부서·직위 (직원명부의 POSITION_TYPO 2건은 명부 쪽 오류)"}},
        "accidents": A.items, "valueNoise": noise,
    })
