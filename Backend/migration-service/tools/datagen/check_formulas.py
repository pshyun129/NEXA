"""
생성기가 수식과 함께 저장한 계산값이 '엑셀이 계산했을 값'과 같은지 확인한다 (개발용).

LibreOffice로 다시 계산·저장하는 대신, 생성기가 파이썬으로 계산값을 직접 넣는다.
그 값이 맞는지 독립 구현인 `formulas` 라이브러리로 통합 문서를 다시 계산해 셀마다 비교한다.
느리므로 필요한 파일만 지정해서 돌린다. requirements.txt에는 넣지 않는다 (pip install formulas).

예외: formulas는 SUMIFS·COUNTIFS 조건이 숫자처럼 보이는 문자("00101")면 #VALUE!를 낸다 (엑셀은 정상 계산).
그래서 이 둘만 '같은 값끼리 더하기·세기'로 바꿔 끼운다. 나머지 함수(ROUND·VLOOKUP·INDEX·DATEDIF 등)는 라이브러리 그대로.

사용: python -I check_formulas.py out/mabaservice/급여대장.xlsx [--sheets 2026-10,2026-11]
"""
import argparse
import json
import math
from pathlib import Path

import formulas
import numpy as np
import openpyxl


def _arr(x):
    return np.asarray(getattr(x, "value", x), dtype=object)  # 범위는 Ranges 객체로 들어온다


def _match(pairs):
    hit = None
    for rng, crit in zip(pairs[::2], pairs[1::2]):
        crit = _arr(crit).ravel()[0]
        m = np.vectorize(lambda x: str(x) == str(crit))(_arr(rng))
        hit = m if hit is None else hit & m
    return hit


def _sumifs(sum_range, *pairs):
    vals = _arr(sum_range)[_match(pairs)]
    return sum(float(v) for v in vals if isinstance(v, (int, float, np.number)))


def _countifs(*pairs):
    return int(_match(pairs).sum())


formulas.get_functions()["SUMIFS"] = _sumifs
formulas.get_functions()["COUNTIFS"] = _countifs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("xlsx")
    ap.add_argument("--sheets", default="")
    ap.add_argument("--out", default=None)
    args = ap.parse_args()
    path = Path(args.xlsx).resolve()
    want = {s for s in args.sheets.split(",") if s}

    cached = openpyxl.load_workbook(path, data_only=True)
    with_f = openpyxl.load_workbook(path)
    model = formulas.ExcelModel().loads(str(path)).finish()
    sol = model.calculate()
    computed = {}
    for k, v in sol.items():
        # 키 예: "'[급여대장.xlsx]2026-10'!N5"  (범위는 건너뜀)
        ref = str(k)
        if "]" not in ref or ":" in ref.split("!")[-1]:
            continue  # 범위, 셀 참조가 없는 상수 수식의 중간 결과
        sheet = ref.split("]")[1].rsplit("!", 1)[0].strip("'")
        computed[(sheet.upper(), ref.rsplit("!", 1)[1].upper())] = v.value[0, 0]

    report = {"file": path.name, "checked": 0, "mismatch": [], "notComputed": 0}
    for ws in with_f.worksheets:
        if want and ws.title not in want:
            continue
        for row in ws.iter_rows():
            for c in row:
                if not (isinstance(c.value, str) and c.value.startswith("=")):
                    continue
                exp = cached[ws.title][c.coordinate].value
                got = computed.get((ws.title.upper(), c.coordinate))
                if got is None:
                    report["notComputed"] += 1
                    continue
                report["checked"] += 1
                if not same(exp, got):
                    report["mismatch"].append({"sheet": ws.title, "cell": c.coordinate, "formula": c.value,
                                               "cached": exp, "recalculated": str(got)})
    out = Path(args.out) if args.out else path.with_suffix(".check.json")
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    print("checked=%d mismatch=%d notComputed=%d -> %s" % (report["checked"], len(report["mismatch"]), report["notComputed"], out.name))


def same(a, b):
    if a is None and b == "":
        return True  # 빈 문자열 결과는 openpyxl이 None으로 읽는다
    if isinstance(b, formulas.functions.XlError) or type(b).__name__ == "XlError":
        return isinstance(a, str) and a == str(b)
    if hasattr(b, "item"):
        b = b.item()
    if isinstance(a, (int, float)) and isinstance(b, (int, float)):
        return math.isclose(a, b, rel_tol=0, abs_tol=1e-6)
    return a == b


if __name__ == "__main__":
    main()
