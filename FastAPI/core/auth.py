"""JWT 인증: 게이트웨이가 넘겨준 Bearer 토큰을 user-service 공개키(JWKS)로 검증.

팀 공통 규칙(nexa-security NexaJwtValidators)과 똑같이 검사한다.
  - RS256 서명 (user-service JWKS 공개키)
  - 만료(허용 오차 0초)
  - type == "access"
  - sub = 양수 사원 ID
"""
import logging
from dataclasses import dataclass, field

import jwt
from fastapi import Depends
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from core.config import settings
from core.exceptions import BusinessException, ErrorCode

log = logging.getLogger(__name__)

# Swagger에 'Authorize' 버튼이 생김. 헤더가 없어도 여기서 에러를 내지 않고 None으로 넘김
_bearer = HTTPBearer(auto_error=False)
_jwks_client: jwt.PyJWKClient | None = None


def _get_jwks_client() -> jwt.PyJWKClient:
    """공개키는 처음 검증할 때 받아와서 캐시 (키가 바뀌어 kid가 달라지면 다시 받아옴)"""
    global _jwks_client
    if _jwks_client is None:
        _jwks_client = jwt.PyJWKClient(settings.jwt_jwks_uri, cache_keys=True, lifespan=3600, timeout=5)
    return _jwks_client


@dataclass(frozen=True)
class AuthUser:
    """요청한 사원 (팀 nexa-security AuthUser와 같은 구성)"""
    employee_id: int
    role: str | None = None
    dept_id: int | None = None
    grade_id: int | None = None
    duty_id: int | None = None
    # 팀원 API를 부를 때 그대로 넘김 (Bearer relay). 로그에 찍히지 않게 repr에서 제외
    token: str | None = field(default=None, repr=False)

    @property
    def is_admin(self) -> bool:
        return self.role == "ADMIN"


def _int_claim(claims: dict, name: str) -> int | None:
    value = claims.get(name)
    return value if isinstance(value, int) else None


def decode_access_token(token: str) -> AuthUser:
    """토큰을 검증하고 사원 정보로 바꿈. 실패하면 401"""
    try:
        signing_key = _get_jwks_client().get_signing_key_from_jwt(token)
        claims = jwt.decode(
            token,
            signing_key.key,
            algorithms=["RS256"],          # 다른 알고리즘(none, HS256 등)으로 바꿔치기 방지
            options={"require": ["exp", "sub"]},
            leeway=0,                      # 만료 허용 오차 0초 (팀 규칙과 동일)
        )
    except jwt.PyJWKClientConnectionError:
        # 공개키를 못 받아옴 = 사용자 잘못이 아니라 연결 문제 → 401이 아니라 503
        log.exception("JWKS 조회 실패: %s", settings.jwt_jwks_uri)
        raise BusinessException(ErrorCode.AUTH_KEY_UNAVAILABLE)
    except jwt.ExpiredSignatureError:
        raise BusinessException(ErrorCode.INVALID_TOKEN, "만료된 토큰입니다.")
    except jwt.PyJWTError:
        raise BusinessException(ErrorCode.INVALID_TOKEN)

    if claims.get("type") != "access":
        raise BusinessException(ErrorCode.INVALID_TOKEN, "access 토큰이 아닙니다.")
    try:
        employee_id = int(claims["sub"])
    except ValueError:
        employee_id = 0
    if employee_id <= 0:
        raise BusinessException(ErrorCode.INVALID_TOKEN, "사원 ID가 올바르지 않습니다.")

    return AuthUser(
        employee_id=employee_id,
        role=claims.get("role"),
        dept_id=_int_claim(claims, "deptId"),
        grade_id=_int_claim(claims, "gradeId"),
        duty_id=_int_claim(claims, "dutyId"),
        token=token,
    )


def get_current_user(cred: HTTPAuthorizationCredentials | None = Depends(_bearer)) -> AuthUser:
    """모든 API에 붙는 인증 의존성"""
    if cred is not None:
        return decode_access_token(cred.credentials)
    # 로컬 개발용: user-service 없이 테스트할 때만 .env에 AUTH_DEV_EMPLOYEE_ID를 넣음 (운영에선 비움)
    if settings.auth_dev_employee_id:
        return AuthUser(employee_id=settings.auth_dev_employee_id)
    raise BusinessException(ErrorCode.UNAUTHORIZED)


def get_user_id(user: AuthUser = Depends(get_current_user)) -> str:
    """기존 코드 호환: 요청한 사원 ID (requested_by 등에 문자열로 저장)"""
    return str(user.employee_id)