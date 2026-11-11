"""경비 정산서 엑셀: 장별 결과(receipts) → 시트 4장 (값으로 계산해 넣음, 수식 없음 → 어떤 미리보기에서도 숫자가 보임).

  정산서          머리(소속·성명·기간·합계) + 장마다 한 줄 + 합계 줄
  계정과목별 합계  계정과목마다 건수·공급가액·부가세·합계
  규정 확인 내역  걸린 조항마다: 판정·계산·근거 문장·판정 이유·요청자 설명·결재 결과
  품목            장마다 품목 (추출 결과)

두 가지:
  검토 결과 (결재 전) : 정산 포함 = 사람이 빼지 않은 장
  정산 내역 (결재 후) : 정산 포함 = 그 장이 들어간 결재 문서가 승인됨
"""
from datetime import date

from openpyxl import Workbook
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.utils import get_column_letter

from ai.receipts.accounts import account_of, attendees_of, headcount_of, purpose_of, verdict_of

HEAD_FILL = PatternFill("solid", fgColor="E8EDF5")
TITLE_FONT = Font(bold=True, size=15)
BOLD = Font(bold=True)
GREY = Font(color="9AA1AB", italic=True)
VERDICT_FILL = {"위반": PatternFill("solid", fgColor="FBE3E1"), "확인 필요": PatternFill("solid", fgColor="FDF1D8")}
TOTAL_FILL = PatternFill("solid", fgColor="F3F5F8")
THIN = Side(style="thin", color="D5DAE1")
BOX = Border(top=THIN, bottom=THIN, left=THIN, right=THIN)
MONEY = "#,##0"
APPROVAL_NAMES = {"APPROVED": "승인", "REJECTED": "반려", "CANCEL": "회수", None: ""}


def _dv(r: dict, name: str):
    f = (r.get("details") or {}).get(name)
    return f.get("value") if isinstance(f, dict) else None


def is_final(receipts: list[dict]) -> bool:
    return any((r.get("approval") or {}).get("status") for r in receipts)


def included(r: dict, final: bool) -> bool:
    if not r.get("include", True):
        return False
    return r.get("settled", (r.get("approval") or {}).get("status") == "APPROVED") if final else True


def _summary(r: dict) -> str:
    """적요: 사용 목적 + 대표 품목"""
    names = [i.get("name") for i in r.get("items") or [] if i.get("name")]
    what = names[0] + (f" 외 {len(names) - 1}" if len(names) > 1 else "") if names else ""
    return " ".join(x for x in [purpose_of(r) or "", what] if x)


def _sheet(ws, headers: list[str], rows: list[list], money_cols: set[int], widths: dict[int, int] | None = None,
           start_row: int = 1, fills: list | None = None, fonts: list | None = None) -> int:
    """표 하나를 그림 → 마지막 행 번호"""
    for c, h in enumerate(headers, start=1):
        cell = ws.cell(row=start_row, column=c, value=h)
        cell.font, cell.fill, cell.border = BOLD, HEAD_FILL, BOX
        cell.alignment = Alignment(horizontal="center", vertical="center", wrap_text=True)
    for i, row in enumerate(rows, start=start_row + 1):
        for c, v in enumerate(row, start=1):
            cell = ws.cell(row=i, column=c, value=v)
            cell.border = BOX
            cell.alignment = Alignment(vertical="top", wrap_text=isinstance(v, str) and len(v) > 30)
            if c in money_cols and isinstance(v, (int, float)):
                cell.number_format = MONEY
            if fills and fills[i - start_row - 1]:
                cell.fill = fills[i - start_row - 1]
            if fonts and fonts[i - start_row - 1]:
                cell.font = fonts[i - start_row - 1]
    for c, h in enumerate(headers, start=1):
        values = [str(r[c - 1]) for r in rows if c - 1 < len(r) and r[c - 1] is not None]
        width = (widths or {}).get(c) or min(max([len(h) * 2] + [len(v) * 1.3 for v in values] + [6]) + 2, 48)
        ws.column_dimensions[get_column_letter(c)].width = width
    ws.freeze_panes = ws.cell(row=start_row + 1, column=1)
    return start_row + len(rows)


def build_settlement(receipts: list[dict], employee: dict | None, dept_name: str | None, run_id: int | None,
                     today: date | None = None) -> tuple[Workbook, dict]:
    """→ (엑셀, 요약 {kind, count, included_count, total})"""
    today = today or date.today()
    final = is_final(receipts)
    kind = "정산 내역" if final else "검토 결과"
    rs = sorted(receipts, key=lambda r: (str(r["fields"].get("date") or ""), r.get("index", 0)))
    inc = [r for r in rs if included(r, final)]
    dates = [str(r["fields"].get("date")) for r in rs if r["fields"].get("date")]
    total = sum(int(r["fields"].get("total") or 0) for r in inc)

    wb = Workbook()
    ws = wb.active
    ws.title = "정산서"
    ws["A1"] = f"경비 {kind}"
    ws["A1"].font = TITLE_FONT
    meta = [("소속", dept_name or ""), ("성명", (employee or {}).get("name") or ""), ("사번", (employee or {}).get("empNo") or ""),
            ("기간", f"{min(dates)} ~ {max(dates)}" if dates else ""), ("작성일", today.isoformat()),
            ("건수", f"전체 {len(rs)}건 · 정산 포함 {len(inc)}건"), ("정산 합계", total),
            ("실행 번호", f"#{run_id}" if run_id else "")]
    for i, (k, v) in enumerate(meta, start=3):
        ws.cell(row=i, column=1, value=k).font = BOLD
        ws.cell(row=i, column=1).fill = HEAD_FILL
        cell = ws.cell(row=i, column=2, value=v)
        if isinstance(v, int):
            cell.number_format = MONEY
            cell.font = BOLD

    headers = ["No", "일자", "계정과목", "적요", "거래처", "사업자번호", "공급가액", "부가세", "합계", "증빙 구분", "결제 수단",
               "승인번호", "인원", "인원 근거", "참석자", "1인당", "판정", "결재 문서", "결재 결과", "정산 포함", "비고"]
    rows, fills, fonts = [], [], []
    for n, r in enumerate(rs, start=1):
        f = r["fields"]
        people, src = headcount_of(r)
        amount = int(f.get("total") or 0)
        approval = r.get("approval") or {}
        policy = r.get("policy") or {}
        again = (f"다시 검토: {policy['previous_verdict']} → {policy.get('verdict')}"
                 if policy.get("rechecked") and policy.get("previous_verdict") != policy.get("verdict") else None)
        note = [x for x in [r.get("requester_note"), again, "정산에서 뺌" if not r.get("include", True) else None,
                            f"No.{r['duplicate_of'] + 1}과 중복 결제" if r.get("duplicate_of") is not None else None] if x]
        rows.append([n, f.get("date"), account_of(r), _summary(r), f.get("store"), _dv(r, "biz_no"), _dv(r, "supply"),
                     _dv(r, "vat"), amount, r.get("evidence_kind") or "확인 필요", _dv(r, "pay_method"), _dv(r, "approval_no"),
                     people, src, attendees_of(r), amount // people if people else None, verdict_of(r),
                     approval.get("doc_no"), APPROVAL_NAMES.get(approval.get("status"), approval.get("status")),
                     "O" if included(r, final) else "X", " / ".join(note)])
        fills.append(VERDICT_FILL.get(verdict_of(r)))
        fonts.append(None if included(r, final) else GREY)
    last = _sheet(ws, headers, rows, {7, 8, 9, 16}, start_row=12, fills=fills, fonts=fonts,
                  widths={1: 5, 2: 11, 4: 22, 5: 20, 15: 22, 21: 26})
    t = last + 1
    ws.cell(row=t, column=1, value="정산 합계").font = BOLD
    sums = {7: sum(_dv(r, "supply") or 0 for r in inc), 8: sum(_dv(r, "vat") or 0 for r in inc), 9: total}
    for c in range(1, len(headers) + 1):
        cell = ws.cell(row=t, column=c)
        cell.fill, cell.border = TOTAL_FILL, BOX
        if c in sums:
            cell.value, cell.number_format, cell.font = sums[c], MONEY, BOLD

    # 계정과목별 합계 (정산 포함만)
    ws2 = wb.create_sheet("계정과목별 합계")
    by: dict[str, list] = {}
    for r in inc:
        a = by.setdefault(account_of(r), [0, 0, 0, 0])
        a[0] += 1
        a[1] += _dv(r, "supply") or 0
        a[2] += _dv(r, "vat") or 0
        a[3] += int(r["fields"].get("total") or 0)
    rows2 = [[k, *v] for k, v in sorted(by.items())]
    rows2.append(["합계", sum(v[0] for v in by.values()), sum(v[1] for v in by.values()),
                  sum(v[2] for v in by.values()), total])
    _sheet(ws2, ["계정과목", "건수", "공급가액", "부가세", "합계"], rows2, {3, 4, 5},
           fonts=[None] * (len(rows2) - 1) + [BOLD], fills=[None] * (len(rows2) - 1) + [TOTAL_FILL])

    # 규정 확인 내역: 위반·확인 필요 조항마다 한 줄
    ws3 = wb.create_sheet("규정 확인 내역")
    rows3, fills3 = [], []
    for n, r in enumerate(rs, start=1):
        for fd in (r.get("policy") or {}).get("findings") or []:
            if fd.get("verdict") not in ("위반", "확인 필요"):
                continue
            approval = r.get("approval") or {}
            rows3.append([n, r["fields"].get("date"), r["fields"].get("store"), int(r["fields"].get("total") or 0),
                          fd.get("label"), fd.get("verdict"), "; ".join(fd.get("numeric") or []), fd.get("quote"),
                          "; ".join([fd.get("reason") or "", *(fd.get("checks") or [])]).strip("; "),
                          r.get("requester_note") or "", APPROVAL_NAMES.get(approval.get("status"), approval.get("status"))])
            fills3.append(VERDICT_FILL.get(fd.get("verdict")))
    if not rows3:
        rows3, fills3 = [["", "", "", None, "걸린 조항 없음", "", "", "", "", "", ""]], [None]
    _sheet(ws3, ["No", "일자", "거래처", "금액", "조항", "판정", "계산", "근거 문장", "판정 이유", "요청자 설명", "결재 결과"],
           rows3, {4}, fills=fills3, widths={1: 5, 5: 30, 8: 44, 9: 44, 10: 30})

    # 품목
    ws4 = wb.create_sheet("품목")
    rows4 = [[n, r["fields"].get("store"), i.get("name"), i.get("quantity"), i.get("amount"), i.get("kind")]
             for n, r in enumerate(rs, start=1) for i in r.get("items") or []]
    _sheet(ws4, ["No", "거래처", "품목", "수량", "금액", "종류"], rows4 or [["", "", "추출된 품목 없음", None, None, ""]], {5})

    return wb, {"kind": kind, "count": len(rs), "included_count": len(inc), "total": total}
