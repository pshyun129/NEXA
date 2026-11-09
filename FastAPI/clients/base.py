"""팀원 서비스 HTTP 호출 공통.

응답 형식 {success, error: {code, message}, data}는 팀 공통이라 여기서 한 번에 처리한다.
"""
import httpx

from core.config import settings


class ServiceError(Exception):
    """팀원 서비스 호출 실패. status가 None이면 연결 자체가 안 된 것"""

    def __init__(self, service: str, status: int | None, code: str | None, message: str):
        self.service = service
        self.status = status
        self.code = code
        self.message = message
        super().__init__(f"[{service}] {status or '연결 실패'} {code or ''} {message}")

    @property
    def retryable(self) -> bool:
        """연결 실패·5xx는 잠시 뒤 다시 해볼 만함, 4xx는 요청 자체가 잘못된 것"""
        return self.status is None or self.status >= 500


def call(
    service: str,
    base_url: str,
    method: str,
    path: str,
    *,
    token: str | None,
    json: dict | None = None,
    params: dict | None = None,
):
    """팀원 API를 호출하고 data만 돌려줌. 실패하면 ServiceError"""
    # 사용자 토큰을 그대로 실어 보냄 (Bearer relay) → 상대 서비스가 sub로 사용자를 식별
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    try:
        with httpx.Client(base_url=base_url, timeout=settings.service_timeout) as client:
            res = client.request(method, path, headers=headers, json=json, params=params)
    except httpx.HTTPError as e:  # 연결 거부, 타임아웃 등
        raise ServiceError(service, None, None, str(e)) from e

    try:
        body = res.json()
    except ValueError:  # JSON이 아닌 응답 (게이트웨이 에러 페이지 등)
        body = {}

    if res.is_success and body.get("success", True):
        return body.get("data")

    error = body.get("error") or {}
    raise ServiceError(service, res.status_code, error.get("code"), error.get("message") or res.text[:200])