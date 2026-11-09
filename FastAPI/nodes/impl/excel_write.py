"""엑셀 작성 노드: 앞 노드의 값(한 건 또는 여러 건)을 .xlsx 파일로 만들어 실행 결과 파일로 남긴다 (LLM 없음).

예) 데이터 {{n3.fields}}, 열 "store:상호, total:금액, date:거래일" → 경비 내역.xlsx
    다운로드: GET /api/v1/ai/runs/files/{file_id}/download (실행 요청자만)
파일은 업로드 파일과 같은 저장소(UPLOAD_DIR)에 두고 RunFile로 기록 → run_id가 있어서 24시간 정리 대상이 아님
"""
import re
import uuid
from datetime import datetime

from openpyxl import Workbook
from openpyxl.styles import Alignment, Font, PatternFill

from core.database import SessionLocal
from nodes.base import BaseNode, NodeContext, NodeResult, latest_output
from runs import files
from runs.models import RunFile

XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
GENERATED_BY = "ai-service"        # uploaded_by에 기록 → 사람이 올린 파일과 구분


def _rows(data) -> list[dict]:
    """한 건(dict) 또는 여러 건(list) → 행 목록. 값이 dict가 아닌 목록이면 '값' 한 열로"""
    if isinstance(data, dict):
        return [data]
    if isinstance(data, list):
        return [r if isinstance(r, dict) else {"값": r} for r in data]
    raise ValueError("엑셀로 만들 데이터가 없습니다 (한 건이면 {{n3.fields}}, 여러 건이면 목록)")


def _columns(spec: str | None, rows: list[dict]) -> list[tuple[str, str]]:
    """'store:상호, total:금액' → [(store, 상호), (total, 금액)]. 비우면 데이터의 키를 그대로 열로"""
    if spec and spec.strip():
        cols = []
        for part in spec.split(","):
            key, _, label = part.strip().partition(":")
            if key.strip():
                cols.append((key.strip(), (label or key).strip()))
        return cols
    keys: list[str] = []
    for r in rows:
        keys += [k for k in r if k not in keys]
    return [(k, k) for k in keys]


def _safe_name(name: str) -> str:
    """파일 이름에 못 쓰는 글자 제거 + .xlsx"""
    name = re.sub(r'[\\/:*?"<>|\n\r\t]', "_", name).strip() or "결과"
    return name if name.lower().endswith(".xlsx") else f"{name}.xlsx"


def build_workbook(rows: list[dict], columns: list[tuple[str, str]], sheet: str) -> Workbook:
    wb = Workbook()
    ws = wb.active
    ws.title = sheet[:31] or "Sheet1"            # 엑셀 시트 이름은 31자까지
    ws.append([label for _, label in columns])
    for cell in ws[1]:                            # 머리글: 굵게 + 배경색
        cell.font = Font(bold=True)
        cell.fill = PatternFill("solid", fgColor="DDEBF7")
        cell.alignment = Alignment(horizontal="center")
    for r in rows:
        ws.append([r.get(key) if not isinstance(r.get(key), (dict, list)) else str(r.get(key)) for key, _ in columns])
    for col_idx, (key, label) in enumerate(columns, start=1):
        values = [ws.cell(row=i, column=col_idx).value for i in range(2, ws.max_row + 1)]
        if values and all(isinstance(v, (int, float)) and not isinstance(v, bool) for v in values if v is not None):
            for i in range(2, ws.max_row + 1):    # 숫자 열: 천 단위 쉼표
                ws.cell(row=i, column=col_idx).number_format = "#,##0"
        width = max([len(str(label))] + [len(str(v)) for v in values if v is not None]) + 4
        ws.column_dimensions[ws.cell(row=1, column=col_idx).column_letter].width = min(width * 1.3, 60)
    ws.freeze_panes = "A2"                        # 머리글 고정
    return wb


class ExcelWriteNode(BaseNode):
    node_type = "excel_write"
    name = "엑셀 작성"
    category = "output"
    description = "앞 노드 값으로 엑셀(.xlsx) 파일을 만들어 실행 결과로 남김 (다운로드 가능)"
    config_fields = [
        {"key": "data", "label": "데이터 (예: {{n3.fields}}, 여러 건이면 목록, 비우면 가장 최근 추출 결과)",
         "type": "ref", "required": False},
        {"key": "columns", "label": "열 (예: store:상호, total:금액, date:거래일 · 비우면 전부)", "type": "text",
         "required": False},
        {"key": "file_name", "label": "파일 이름 (예: 경비내역_{{n3.fields.date}})", "type": "text", "required": False},
        {"key": "sheet", "label": "시트 이름", "type": "text", "required": False},
    ]
    branches = []
    output_vars = [
        {"key": "file_id", "label": "파일 ID", "type": "number"},
        {"key": "file_name", "label": "파일 이름", "type": "text"},
        {"key": "download_url", "label": "다운로드 주소", "type": "text"},
        {"key": "row_count", "label": "행 수", "type": "number"},
    ]

    def run(self, ctx: NodeContext) -> NodeResult:
        data = ctx.config.get("data") or (latest_output(ctx.outputs, "fields") or {}).get("fields")
        rows = _rows(data)
        columns = _columns(ctx.config.get("columns"), rows)
        name = _safe_name(ctx.config.get("file_name") or f"결과_{datetime.now():%Y%m%d_%H%M}")
        wb = build_workbook(rows, columns, ctx.config.get("sheet") or "결과")

        files.UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
        stored = f"{uuid.uuid4().hex}.xlsx"
        path = files.file_path(stored)
        wb.save(path)

        with SessionLocal() as db:
            rf = RunFile(run_id=ctx.run_id, original_name=name, stored_name=stored, content_type=XLSX,
                         size=path.stat().st_size, uploaded_by=GENERATED_BY)
            db.add(rf)
            db.commit()
            db.refresh(rf)
            file_id = rf.id
        return NodeResult(output={"file_id": file_id, "file_name": name, "row_count": len(rows),
                                  "download_url": f"/api/v1/ai/runs/files/{file_id}/download"})