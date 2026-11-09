"""실행 중 팀원 API를 부를 때 쓸 사용자 토큰 보관소.

- DB·체크포인트에 저장하지 않고 이 프로세스 메모리에만 둠 (토큰 유출 방지)
- 실행 시작·재개 요청의 토큰으로 넣고, 그 구간이 끝나면(WAITING/완료/실패) 지움
- 서버가 재시작되면 사라지지만, 재개 요청이 새 토큰을 다시 넣어 줌
"""
import threading

_tokens: dict[int, str] = {}
_lock = threading.Lock()   # 실행이 여러 스레드(BackgroundTasks)에서 동시에 돌 수 있음


def set_token(run_id: int, token: str | None) -> None:
    if not token:
        return
    with _lock:
        _tokens[run_id] = token


def get_token(run_id: int) -> str | None:
    with _lock:
        return _tokens.get(run_id)


def clear_token(run_id: int) -> None:
    with _lock:
        _tokens.pop(run_id, None)