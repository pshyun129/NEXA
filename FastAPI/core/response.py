from typing import Generic, TypeVar

from pydantic import BaseModel

T = TypeVar("T")


class ErrorBody(BaseModel):
    code: str
    message: str


class ApiResult(BaseModel, Generic[T]):
    success: bool
    error: ErrorBody | None = None
    data: T | None = None


def ok(data=None) -> dict:
    return {"success": True, "error": None, "data": data}