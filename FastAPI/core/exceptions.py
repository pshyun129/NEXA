import logging
from enum import Enum

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

log = logging.getLogger(__name__)


class ErrorCode(Enum):
    # 공통 에러 코드
    INVALID_INPUT = (400, "COMMON_001", "요청 값이 올바르지 않습니다.")
    NOT_FOUND = (404, "COMMON_003", "요청한 리소스를 찾을 수 없습니다.")
    INTERNAL_ERROR = (500, "COMMON_999", "서버 오류가 발생했습니다.")

    # 인증
    UNAUTHORIZED = (401, "AUTH_001", "인증이 필요합니다.")
    INVALID_TOKEN = (401, "AUTH_002", "유효하지 않은 토큰입니다.")
    AUTH_KEY_UNAVAILABLE = (503, "AUTH_003", "인증 서버에 연결할 수 없습니다.")

    #노드
    NODE_NOT_FOUND = (404, "NODE_001", "존재하지 않는 노드입니다.")

    # 워크플로우
    WF_NOT_FOUND = (404, "WF_001", "존재하지 않는 워크플로우입니다.")
    WF_VERSION_NOT_FOUND = (404, "WF_002", "존재하지 않는 버전입니다.")
    WF_INVALID_GRAPH = (400, "WF_003", "워크플로우 흐름에 오류가 있습니다.")
    WF_NOT_DEPLOYED = (409, "WF_004", "배포되지 않은 워크플로우입니다.")
    WF_HAS_RUNS = (409, "WF_005", "실행 기록이 있는 워크플로우는 삭제할 수 없습니다.")

    # 워크플로우 실행
    RUN_NOT_FOUND = (404, "RUN_001", "존재하지 않는 실행입니다.")
    RUN_ALREADY_FINISHED = (409, "RUN_002", "이미 끝난 실행입니다.")
    RUN_MISSING_INPUT = (400, "RUN_003", "필수 입력값이 비어 있습니다.")
    RUN_NOT_WAITING = (409, "RUN_004", "확인 대기 중인 실행이 아닙니다.")
    RUN_FORBIDDEN = (403, "RUN_005", "본인이 요청한 실행만 처리할 수 있습니다.")
    RUN_WAITING_APPROVAL = (409, "RUN_006", "결재 결과를 기다리는 중이라 직접 재개할 수 없습니다.")

    # 첨부 파일
    FILE_NOT_FOUND = (404, "FILE_001", "존재하지 않는 파일입니다")
    FILE_ALREADY_USED = (409, "FILE_002", "이미 실행에 사용된 파일입니다.")
    FILE_TOO_LARGE = (413, "FILE_003", "파일 용량이 너무 큽니다.")


    def __init__(self, status: int, code: str, message: str):
        self.status = status
        self.code = code
        self.message = message


class BusinessException(Exception):
    def __init__(self, error_code: ErrorCode, message: str | None = None):
        self.error_code = error_code
        self.message = message or error_code.message


def _fail(error_code: ErrorCode, message: str | None = None) -> JSONResponse:
    return JSONResponse(
        status_code=error_code.status,
        content={
            "success": False,
            "error": {"code": error_code.code, "message": message or error_code.message},
            "data": None,
        },
    )


def register_exception_handlers(app: FastAPI) -> None:

    # 우리가 직접 던진 에러
    @app.exception_handler(BusinessException)
    async def handle_business(request: Request, e: BusinessException):
        return _fail(e.error_code, e.message)

    # 요청 값 형식 오류
    @app.exception_handler(RequestValidationError)
    async def handle_validation(request: Request, e: RequestValidationError):
        detail = ", ".join(
            f"{'.'.join(str(x) for x in err['loc'][1:])}: {err['msg']}" for err in e.errors()
        )
        return _fail(ErrorCode.INVALID_INPUT, detail)

    # 없는 주소 등 FastAPI가 내는 에러
    @app.exception_handler(StarletteHTTPException)
    async def handle_http(request: Request, e: StarletteHTTPException):
        if e.status_code == 404:
            return _fail(ErrorCode.NOT_FOUND)
        return JSONResponse(
            status_code=e.status_code,
            content={
                "success": False,
                "error": {"code": f"HTTP_{e.status_code}", "message": str(e.detail)},
                "data": None,
            },
        )

    # 예상 못 한 에러 (코드 버그 등)
    @app.exception_handler(Exception)
    async def handle_unexpected(request: Request, e: Exception):
        log.exception("Unhandled exception")
        return _fail(ErrorCode.INTERNAL_ERROR)