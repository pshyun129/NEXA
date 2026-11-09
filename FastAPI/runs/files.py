import uuid
from pathlib import Path

from fastapi import UploadFile

from core.config import settings
from core.exceptions import BusinessException, ErrorCode

UPLOAD_DIR = Path(settings.upload_dir)
CHUNK_SIZE = 1024 * 1024 # 1MB씩 읽기


def save_upload(file: UploadFile) -> tuple[str, int]:
    UPLOAD_DIR.mkdir(parents=True, exist_ok=True)

    suffix = Path(file.filename or "").suffix.lower()
    stored_name = f"{uuid.uuid4().hex}{suffix}"
    path = UPLOAD_DIR / stored_name

    max_bytes = settings.max_upload_mb * 1024 * 1024
    size = 0
    too_large = False

    with path.open("wb") as out:
        while chunk := file.file.read(CHUNK_SIZE):
            size += len(chunk)
            if size > max_bytes:
                too_large = True
                break
            out.write(chunk)

    if too_large:
        path.unlink(missing_ok=True)
        raise BusinessException(ErrorCode.FILE_TOO_LARGE, f"파일은 {settings.max_upload_mb}MB까지 올릴 수 있습니다.")

    return stored_name, size


def file_path(stored_name: str) -> Path:
    return UPLOAD_DIR / stored_name


def delete_stored(stored_name: str) -> None:
    file_path(stored_name).unlink(missing_ok=True)