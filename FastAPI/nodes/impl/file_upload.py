"""파일 업로드 노드: 실행할 때 첨부한 파일 중 허용 형식만 골라 다음 노드로 넘긴다."""
from pathlib import Path

from nodes.base import BaseNode, NodeContext, NodeResult

ACCEPT_EXTS = {
    "이미지": {".jpg", ".jpeg", ".png", ".bmp", ".webp"},
    "PDF": {".pdf"},
    "엑셀": {".xlsx", ".xls", ".csv"},
}
# 실행 폼·오류 문구에 보여 줄 형식 설명
ACCEPT_HINT = {"이미지": "jpg·png·bmp·webp", "PDF": "pdf", "엑셀": "xlsx·xls·csv"}


def file_requirements(graph: dict | None) -> list[dict]:
    """그래프의 파일 업로드 노드가 요구하는 첨부 [{node_id, accept, exts, min_count}].
    실행 폼(첨부 칸 자동 추가)과 실행 시작 검사(첨부 없으면 거절)가 같이 쓴다"""
    reqs = []
    for node in (graph or {}).get("nodes", []):
        if node.get("type") != "file_upload":
            continue
        config = node.get("config") or {}
        accept = config.get("accept")
        reqs.append({
            "node_id": node.get("id"),
            "accept": accept,
            "exts": ACCEPT_EXTS.get(accept),          # 형식을 아직 안 고른 초안이면 None = 형식 무관
            "min_count": int(config.get("min_count") or 1),
        })
    # 'OCR할 파일'을 비운 OCR 노드도 실행 첨부 파일을 직접 읽는다 (빌더 화면의 fileNode와 같은 기준)
    if not reqs:
        for node in (graph or {}).get("nodes", []):
            if node.get("type") == "ocr_extract" and not str((node.get("config") or {}).get("files") or "").strip():
                reqs.append({"node_id": node.get("id"), "accept": "이미지", "exts": ACCEPT_EXTS["이미지"], "min_count": 1})
                break
    return reqs


class FileUploadNode(BaseNode):
    node_type = "file_upload"
    name = "파일 업로드"
    category = "input"
    description = "실행할 때 첨부한 파일 중 허용 형식만 골라 넘김"
    config_fields = [
        {"key": "accept", "label": "허용 형식", "type": "select", "options": list(ACCEPT_EXTS), "required": True},
        {"key": "min_count", "label": "최소 파일 수 (기본 1)", "type": "number", "required": False},
    ]
    branches = []
    output_vars = [
        {"key": "files", "label": "파일 목록", "type": "list"},
        {"key": "count", "label": "파일 수", "type": "number"},
    ]

    def run(self, ctx: NodeContext) -> NodeResult:
        accept = ctx.config["accept"]
        exts = ACCEPT_EXTS[accept]
        files = [f for f in ctx.inputs.get("files", []) if Path(f["path"]).suffix.lower() in exts]

        min_count = int(ctx.config.get("min_count") or 1)
        if len(files) < min_count:
            raise ValueError(f"{accept} 파일이 {min_count}개 이상 필요합니다 (받은 파일: {len(files)}개)")
        return NodeResult(output={"files": files, "count": len(files)})