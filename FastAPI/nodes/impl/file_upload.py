"""파일 업로드 노드: 실행할 때 첨부한 파일 중 허용 형식만 골라 다음 노드로 넘긴다."""
from pathlib import Path

from nodes.base import BaseNode, NodeContext, NodeResult

ACCEPT_EXTS = {
    "이미지": {".jpg", ".jpeg", ".png", ".bmp", ".webp"},
    "PDF": {".pdf"},
    "엑셀": {".xlsx", ".xls", ".csv"},
}


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